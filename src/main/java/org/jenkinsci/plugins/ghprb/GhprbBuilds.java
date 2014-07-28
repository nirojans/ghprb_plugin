package org.jenkinsci.plugins.ghprb;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Cause.UpstreamCause;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.util.BuildData;
import jenkins.model.Jenkins;

import org.jenkinsci.plugins.ghprb.AutoMergeProcessor.DownstreamBuilds;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestMergeResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author janinko
 */
public class GhprbBuilds {
	private static final Logger logger = Logger.getLogger(GhprbBuilds.class
			.getName());
	private GhprbTrigger trigger;
	private GhprbRepository repo;

	// himan added variables
	private List<DownstreamBuilds> downstreamBuilds;

	private Map<String, AbstractBuild> buildDetails;
	
	public void setBuildDetails(Map<String, AbstractBuild> buildDetails) {
		this.buildDetails = buildDetails;
	}

	public Map<String, AbstractBuild> getBuildDetails() {
		return buildDetails;
	}

	private AbstractBuild mainBuild;

	// --

	public AbstractBuild getMainBuild() {
		return mainBuild;
	}

	public void setMainBuild(AbstractBuild mainBuild) {
		this.mainBuild = mainBuild;
	}

	public List<DownstreamBuilds> getDownstreamBuilds() {
		return downstreamBuilds;
	}

	public void setDownstreamBuilds(List<DownstreamBuilds> downstreamBuilds) {
		this.downstreamBuilds = downstreamBuilds;
	}

	public void autoMerging() {

	}

//	public void addBuildDetails(String projectName,
//			AbstractBuild downstreamBuild) {
//		if (buildDetails == null) {
//			buildDetails = new ConcurrentHashMap<String, AbstractBuild>();
//		}
//
//		logger.log(Level.SEVERE,
//				"@@@@@@@@@   Add Build details -> Project name :" + projectName
//						+ " build no :" + downstreamBuild.getNumber());
//
//		if (downstreamBuild.getResult() == Result.FAILURE) {
//			logger.log(Level.INFO, "------------ build faild !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
//			buildDetails=null;
//			
//		} else if (downstreamBuild.getResult() == Result.UNSTABLE) {
//			logger.log(Level.INFO, "------------ unstable");
//			buildDetails=null;
//		} else {
//			buildDetails.put(projectName, downstreamBuild);
//			logger.log(Level.INFO, "------------ GHPRBBUILDS else");
//			if (buildDetails.size() == downstreamBuilds.size()) {
//				// competed and merge
//				logger.log(Level.INFO,
//						"------------ All completed and successed");
//				merge(mainBuild);
//				buildDetails=null;
//			}
//
//		}
//
//	}

	public GhprbBuilds(GhprbTrigger trigger, GhprbRepository repo) {
		this.trigger = trigger;
		this.repo = repo;

		logger.log(Level.SEVERE,
				"------------Ghprbbuild constructor GhprbRepository repo :"
						+ repo.getName() + " trigger :" + trigger);
	}

	public String build(GhprbPullRequest pr) {
		StringBuilder sb = new StringBuilder();
		if (cancelBuild(pr.getId())) {
			sb.append("Previous build stopped.");
		}

		if (pr.isMergeable()) {
			sb.append(" Merged build triggered.");
		} else {
			sb.append(" Build triggered.");
		}

		GhprbCause cause = new GhprbCause(pr.getHead(), pr.getId(),
				pr.isMergeable(), pr.getTarget(), pr.getSource(),
				pr.getAuthorEmail(), pr.getTitle(), pr.getUrl());

		QueueTaskFuture<?> build = trigger.startJob(cause, repo);
		if (build == null) {
			logger.log(Level.SEVERE, "Job did not start");
		}
		return sb.toString();
	}

	private boolean cancelBuild(int id) {
		return false;
	}

	private GhprbCause getCause(AbstractBuild build) {
		Cause cause = build.getCause(GhprbCause.class);
		if (cause == null || (!(cause instanceof GhprbCause)))
			return null;
		return (GhprbCause) cause;
	}

	public void onStarted(AbstractBuild build) {

		logger.log(Level.SEVERE, "------------Ghprbbuild onstarted");

//		// set the downstream builds for the PR triggered project
//		if (downstreamBuilds == null) {
//			downstreamBuilds = new AutoMergeProcessor()
//					.getDownstreamBuildList(build);
//		}

		GhprbCause c = getCause(build);
		if (c == null)
			return;

		repo.createCommitStatus(build, GHCommitState.PENDING,
				(c.isMerged() ? "Merged build started." : "Build started."),
				c.getPullID());
		try {
			build.setDescription("<a title=\"" + c.getTitle() + "\" href=\""
					+ repo.getRepoUrl() + "/pull/" + c.getPullID() + "\">PR #"
					+ c.getPullID() + "</a>: " + c.getAbbreviatedTitle());
		} catch (IOException ex) {
			logger.log(Level.SEVERE, "Can't update build description", ex);
		}
	}

	public void onCompleted(AbstractBuild build) {
		GhprbCause c = getCause(build);
		if (c == null)
			return;

		// remove the BuildData action that we may have added earlier to avoid
		// having two of them, and because the one we added isn't correct
		// @see GhprbTrigger
		BuildData fakeOne = null;
		for (BuildData data : build.getActions(BuildData.class)) {
			if (data.getLastBuiltRevision() != null
					&& !data.getLastBuiltRevision().getSha1String()
							.equals(c.getCommit())) {
				fakeOne = data;
				break;
			}
		}
		if (fakeOne != null) {
			build.getActions().remove(fakeOne);
		}

		logger.log(Level.SEVERE, "----------------- finished");

		merge();
		// --

	}

	public void merge() {
		GhprbCause c = getCause(mainBuild);
		if (c == null)
			return;

		GHCommitState state;
		if (mainBuild.getResult() == Result.SUCCESS) {
			state = GHCommitState.SUCCESS;
		} else if (mainBuild.getResult() == Result.UNSTABLE) {
			state = GHCommitState.valueOf(GhprbTrigger.getDscp()
					.getUnstableAs());
		} else {
			state = GHCommitState.FAILURE;
		}
		if (state == GHCommitState.FAILURE) {
			repo.addComment(
					c.getPullID(),
					"Build failed. Pull ID: " + c.getPullID() + "\nGithub : "
							+ mainBuild.getDescription() + "\nJenkins: "
							+ Jenkins.getInstance().getRootUrl()
							+ mainBuild.getUrl());
		}

		repo.createCommitStatus(mainBuild, state,
				(c.isMerged() ? "Merged build finished." : "Build finished."),
				c.getPullID());

		String publishedURL = GhprbTrigger.getDscp().getPublishedURL();
		if (publishedURL != null && !publishedURL.isEmpty()) {
			StringBuilder msg = new StringBuilder();

			if (state == GHCommitState.SUCCESS) {
				msg.append(GhprbTrigger.getDscp().getMsgSuccess());
			} else {
				msg.append(GhprbTrigger.getDscp().getMsgFailure());
			}
			msg.append("\nRefer to this link for build results: ");
			msg.append(publishedURL).append(mainBuild.getUrl());

			int numLines = GhprbTrigger.getDscp().getlogExcerptLines();
			if (state != GHCommitState.SUCCESS && numLines > 0) {
				// on failure, append an excerpt of the build log
				try {
					// wrap log in "code" markdown
					msg.append("\n\n**Build Log**\n*last ").append(numLines)
							.append(" lines*\n");
					msg.append("\n ```\n");
					List<String> log = mainBuild.getLog(numLines);
					for (String line : log) {
						msg.append(line).append('\n');
					}
					msg.append("```\n");
				} catch (IOException ex) {
					logger.log(Level.WARNING,
							"Can't add log excerpt to commit comments", ex);
				}
			}

			repo.addComment(c.getPullID(), msg.toString());
		}

		// close failed pull request automatically
		if (state == GHCommitState.FAILURE
				&& trigger.isAutoCloseFailedPullRequests()) {

			try {
				GHPullRequest pr = repo.getPullRequest(c.getPullID());

				if (pr.getState().equals(GHIssueState.OPEN)) {
					repo.closePullRequest(c.getPullID());
				}
			} catch (IOException ex) {
				logger.log(Level.SEVERE, "Can't close pull request", ex);
			}
		}

		if (state == GHCommitState.SUCCESS
				&& trigger.getAutoMergePullRequests()) {
			try {
				GHPullRequestMergeResponse mergeRequest;
				int attemptCount = 0;
				do {
					mergeRequest = repo.getMergeRequest(c.getPullID(),
							"sucessfully commited the changes"
									+ "\nTimestamp : " + mainBuild.getTime() + "\n"
									+ Jenkins.getInstance().getRootUrl()
									+ mainBuild.getUrl() + "\n");

					attemptCount++;

					if (mergeRequest.isMerged()) {
						logger.log(
								Level.INFO,
								"Successfully merged pull request "
										+ c.getPullID() + " SHA: "
										+ mergeRequest.getSha()
										+ ". Message is: "
										+ mergeRequest.getMessage());
						break;
					} else {
						Thread.sleep(10000);
					}
				} while (attemptCount != 3);

				if (!mergeRequest.isMerged()) {
					logger.log(
							Level.WARNING,
							"Could not merge pull request " + c.getPullID()
									+ ". Error is: "
									+ mergeRequest.getMessage());
				}
			}
			/**
			 * Change the exception for catch
			 */
			catch (Exception ex) {
				logger.log(Level.SEVERE, "Can't Merge pull request", ex);
			}
		}
	}

}
