package com.amazon.jenkins.ec2fleet;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Cause;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.queue.SubTask;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is wrapper for {@link ComputerLauncher} to get notification when slave was disconnected
 * and automatically resubmit {@link hudson.model.Queue.Task} if reason is unexpected termination
 * which usually means EC2 instance was interrupted.
 * <p>
 * This is optional feature, it's enabled by default, but could be disabled by
 * {@link EC2FleetCloud#isDisableTaskResubmit()}
 *
 * @see EC2FleetNode
 * @see EC2FleetNodeComputer
 */
@SuppressWarnings("WeakerAccess")
@ThreadSafe
public class EC2FleetAutoResubmitComputerLauncher extends DelegatingComputerLauncher {

    private static final Level LOG_LEVEL = Level.INFO;
    private static final Logger LOGGER = Logger.getLogger(EC2FleetAutoResubmitComputerLauncher.class.getName());

    /**
     * Delay which will be applied when job {@link Queue#scheduleInternal(Queue.Task, int, List)}
     * rescheduled after offline
     */
    private static final int RESCHEDULE_QUIET_PERIOD_SEC = 10;

    public EC2FleetAutoResubmitComputerLauncher(final ComputerLauncher launcher) {
        super(launcher);
    }

    /**
     * {@link ComputerLauncher#afterDisconnect(SlaveComputer, TaskListener)}
     * <p>
     * EC2 Fleet plugin overrides this method to detect jobs which were failed because of
     * EC2 instance was terminated/stopped. It could be manual stop or because of Spot marked.
     * In all cases as soon as job aborted because of broken connection and slave is offline
     * it will try to resubmit aborted job back to the queue, so user doesn't need to do that manually
     * and another slave could take it.
     * <p>
     * Implementation details
     * <p>
     * There is no official recommendation about way how to resubmit job according to
     * https://issues.jenkins-ci.org/browse/JENKINS-49707 moreover some of Jenkins code says it impossible.
     * <p>
     * method checks {@link SlaveComputer#getOfflineCause()} for disconnect because of EC2 instance termination
     * it returns
     * <code>
     * result = {OfflineCause$ChannelTermination@13708} "Connection was broken: java.io.IOException:
     * Unexpected termination of the channel\n\tat hudson.remoting.SynchronousCommandTransport$ReaderThread...
     * cause = {IOException@13721} "java.io.IOException: Unexpected termination of the channel"
     * timestamp = 1561067177837
     * </code>
     *
     * @param computer computer
     * @param listener listener
     */
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "to ignore EC2FleetNodeComputer cast")
    @Override
    public void afterDisconnect(final SlaveComputer computer, final TaskListener listener) {
        // according to jenkins docs could be null in edge cases, check ComputerLauncher.afterDisconnect
        if (computer == null) return;

        // in some multi-thread edge cases cloud could be null for some time, just be ok with that
        final AbstractEC2FleetCloud cloud = ((EC2FleetNodeComputer) computer).getCloud();
        if (cloud == null) {
            LOGGER.warning("Edge case cloud is null for computer " + computer.getDisplayName()
                    + " should be autofixed in a few minutes, if no please create issue for plugin");
            return;
        }

        final boolean unexpectedDisconnect = computer.isOffline() && computer.getOfflineCause() instanceof OfflineCause.ChannelTermination;
        if (!cloud.isDisableTaskResubmit() && unexpectedDisconnect) {
            final List<Executor> executors = computer.getExecutors();
            LOGGER.log(LOG_LEVEL, "Unexpected " + computer.getDisplayName()
                    + " termination,  resubmit");

            for (Executor executor : executors) {
                if (executor.getCurrentExecutable() != null) {
                    executor.interrupt(Result.ABORTED, new EC2TerminationCause(computer.getDisplayName()));

                    final Queue.Executable executable = executor.getCurrentExecutable();
                    // if executor is not idle
                    if (executable != null) {
                        final SubTask subTask = executable.getParent();
                        final Queue.Task task = subTask.getOwnerTask();

                        List<Action> actions = new ArrayList<>();
                        if (executable instanceof Actionable) {
                            actions = ((Actionable) executable).getActions();
                        }
                        // The Priority Sorter plugin can set jobs to different (ie. higher) priority if they are launched from User instead of automatic
                        if (task instanceof AbstractProject) {
                            ((AbstractProject) task).scheduleBuild2(RESCHEDULE_QUIET_PERIOD_SEC, new Cause.UserIdCause(), actions);
                        }
                        else {
                        Queue.getInstance().schedule2(task, RESCHEDULE_QUIET_PERIOD_SEC, actions);
                        }
                        LOGGER.log(LOG_LEVEL, "Unexpected " + computer.getDisplayName()
                                + " termination, resubmit " + task + " with actions " + actions);
                    }
                }
            }
            LOGGER.log(LOG_LEVEL, "Unexpected " + computer.getDisplayName()
                    + " termination, resubmit finished");
        } else {
            LOGGER.log(LOG_LEVEL, "Unexpected " + computer.getDisplayName()
                    + " termination but resubmit disabled, no actions, disableTaskResubmit: "
                    + cloud.isDisableTaskResubmit() + ", offline: " + computer.isOffline()
                    + ", offlineCause: " + (computer.getOfflineCause() != null ? computer.getOfflineCause().getClass() : "null"));
        }

        // call parent
        super.afterDisconnect(computer, listener);
    }

}
