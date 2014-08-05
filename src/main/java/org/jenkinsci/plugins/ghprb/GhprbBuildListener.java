package org.jenkinsci.plugins.ghprb;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.ghprb.AutoMergeProcessor.DownstreamBuilds;
import org.kohsuke.github.GHCommitState;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.listeners.RunListener;
import hudson.triggers.Trigger;

/**
 * 
 * @author janinko
 */
@Extension
public class GhprbBuildListener extends RunListener<AbstractBuild> {
	/** The Logger. */
	private static final Logger LOG = Logger.getLogger(GhprbBuildListener.class
			.getName());
	private Map<GhprbTrigger, GhprbBuilds> triggerList = new ConcurrentHashMap<GhprbTrigger, GhprbBuilds>();
	private AbstractBuild failedMainBuild = null;

	@Override
	public void onStarted(AbstractBuild build, TaskListener listener) {
		// check build fail or not

		GhprbTrigger trigger = GhprbTrigger.getTrigger(build.getProject());
		if (trigger == null)
			return;

		trigger.getGhprb().getBuilds().onStarted(build);
	}

	@Override
	public void onCompleted(AbstractBuild build, TaskListener listener) {
		if (build instanceof AbstractBuild) {
			LOG.log(Level.INFO, "@@@@@@@@@@@@@@@@@ GhprbBuildListener complete");

			Set<GhprbTrigger> triggerListKeySet = triggerList.keySet();
			LOG.log(Level.INFO, "@@@@@@@@@@@@@@@@@ trigger list size :"
					+ triggerList.size());
			trigger_forloop: for (GhprbTrigger t : triggerListKeySet) {

			
			try{
				List<DownstreamBuilds> downstreamList = t.getGhprb()
						.getBuilds().getDownstreamBuilds();
				LOG.log(Level.INFO,
						"@@@@@@@@@@@@@@@@@ downstream build list size :"
								+ downstreamList.size());
				for (DownstreamBuilds db : downstreamList) {
					LOG.log(Level.INFO,
							"@@@@@@@@@@@@@@@@@ downstream build name  :"
									+ db.getProjectName());
					LOG.log(Level.INFO,
							"@@@@@@@@@@@@@@@@@ downstream build main project name  :"
									+ t.getGhprb().getBuilds().getMainBuild()
											.getProject().getName());
					if (db.getProjectName() == build.getProject().getName()) {
						AbstractBuild mainBuild = t.getGhprb().getBuilds()
								.getMainBuild();

						// check whether it is
						boolean returnStatus = findCause(build, mainBuild);
						LOG.log(Level.INFO,
								"################ Cause returns status :"
										+ returnStatus);
						if (returnStatus) {
							// ----
							if (t.getGhprb().getBuilds().getBuildDetails() == null) {
								t.getGhprb()
										.getBuilds()
										.setBuildDetails(
												new ConcurrentHashMap<String, AbstractBuild>());
							}

							LOG.log(Level.SEVERE,
									"@@@@@@@@@   Add Build details -> Project name :"
											+ build.getProject().getName()
											+ " build no :" + build.getNumber());

							if (build.getResult() == Result.FAILURE) {
								failedMainBuild = mainBuild;
								LOG.log(Level.INFO,
										"------------ build faild !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
								t.getGhprb().getBuilds()
										.merge(GHCommitState.FAILURE, build);
								t.getGhprb().getBuilds().setBuildDetails(null);

							} else if (build.getResult() == Result.UNSTABLE) {
								LOG.log(Level.INFO, "------------ unstable");
								t.getGhprb()
										.getBuilds()
										.merge(GHCommitState.valueOf(GhprbTrigger
												.getDscp().getUnstableAs()),
												build);
								t.getGhprb().getBuilds().setBuildDetails(null);
							} else {
								t.getGhprb()
										.getBuilds()
										.getBuildDetails()
										.put(build.getProject().getName(),
												build);
								LOG.log(Level.INFO,
										"------------ GHPRBBUILDS else");
								if (t.getGhprb().getBuilds().getBuildDetails()
										.size() == t.getGhprb().getBuilds()
										.getDownstreamBuilds().size()) {
									// competed and merge
									LOG.log(Level.INFO,
											"------------ All completed and successed");
									t.getGhprb()
											.getBuilds()
											.merge(GHCommitState.SUCCESS, build);
									t.getGhprb().getBuilds()
											.setBuildDetails(null);
								}

							}

							// ----

							break trigger_forloop;

						}

					}
				}
			}catch(NullPointerException e){
				triggerList.remove(t);
			}

			}

			GhprbTrigger trigger = GhprbTrigger.getTrigger(build.getProject());// original

			if (trigger != null) {
				LOG.log(Level.INFO, "@@@@@@@@@@@@@@@@@ trigger if");
				trigger.getGhprb().getBuilds().setMainBuild(build);
				// set the downstream builds for the PR triggered project
				if (trigger.getGhprb().getBuilds().getDownstreamBuilds() == null) {
					trigger.getGhprb()
							.getBuilds()
							.setDownstreamBuilds(
									new AutoMergeProcessor()
											.getDownstreamBuildList(build));
				}

				if (!triggerList.containsKey(trigger)) {
					LOG.log(Level.INFO,
							"@@@@@@@@@@@@@@@@@ trigger if containskey");
					triggerList.put(trigger, trigger.getGhprb().getBuilds());
				}

				boolean isCaused = findCause(build, trigger.getGhprb()
						.getBuilds().getMainBuild());
				if (isCaused) {
					if (build.getResult() == Result.FAILURE) {
						
						LOG.log(Level.INFO,
								"------------ build faild !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

						trigger.getGhprb().getBuilds()
								.merge(GHCommitState.FAILURE, build);
						trigger.getGhprb().getBuilds().setBuildDetails(null);

					} else if (build.getResult() == Result.UNSTABLE) {
						LOG.log(Level.INFO, "------------ unstable");
						trigger.getGhprb()
								.getBuilds()
								.merge(GHCommitState.valueOf(GhprbTrigger
										.getDscp().getUnstableAs()), build);
						trigger.getGhprb().getBuilds().setBuildDetails(null);
					} else {
						if (trigger.getGhprb().getBuilds()
								.getDownstreamBuilds().size() == 0) {
							LOG.log(Level.INFO, "------------ GHPRBBUILDS else");

							// competed and merge
							LOG.log(Level.INFO,
									"------------ All completed and successed");
							trigger.getGhprb().getBuilds()
									.merge(GHCommitState.SUCCESS, build);
							trigger.getGhprb().getBuilds()
									.setBuildDetails(null);

							LOG.log(Level.INFO,
									"@@@@@@@@@@@@@@@@@ No children in this "
											+ trigger.getGhprb().getBuilds()
													.getMainBuild()
											+ " main project");
						}
					}

				}

			}
		} else {
			LOG.log(Level.INFO, "@@@@@@@@@@@@@@@@@ not a abstract build");
		}
	}


	private boolean findCause(AbstractBuild<?, ?> currentBuild,
			AbstractBuild<?, ?> mainBuild) {
		boolean status = false;
		for (Cause c : currentBuild.getCauses()) {
			if (status == true) {
				return status;
			}
			if (!(c instanceof UpstreamCause)) {
				LOG.log(Level.INFO, "################ Cause if failed");
				if (currentBuild == mainBuild) {
					status = true;
					return status;
				}
			} else {
				UpstreamCause upcause = (UpstreamCause) c;
				String upProjectName = upcause.getUpstreamProject();
				int buildNumber = upcause.getUpstreamBuild();
				LOG.log(Level.INFO, "################ Cause Project name :"
						+ upProjectName + " Build no :" + buildNumber);
				AbstractProject upproject = Hudson
						.getInstance()
						.getItemByFullName(upProjectName, AbstractProject.class);
				AbstractBuild upBuild = (AbstractBuild) upproject
						.getBuildByNumber(buildNumber);
				status = findCause(upBuild, mainBuild);
			}
		}
		return status;
	}

}