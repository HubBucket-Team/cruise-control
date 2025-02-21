/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 *
 */

package com.linkedin.kafka.cruisecontrol.analyzer.goals;

import com.linkedin.kafka.cruisecontrol.analyzer.OptimizationOptions;
import com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance;
import com.linkedin.kafka.cruisecontrol.analyzer.ActionType;
import com.linkedin.kafka.cruisecontrol.analyzer.AnalyzerUtils;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingConstraint;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingAction;
import com.linkedin.kafka.cruisecontrol.common.Statistic;
import com.linkedin.kafka.cruisecontrol.exception.OptimizationFailureException;
import com.linkedin.kafka.cruisecontrol.model.Broker;
import com.linkedin.kafka.cruisecontrol.model.ClusterModel;
import com.linkedin.kafka.cruisecontrol.model.ClusterModelStats;
import com.linkedin.kafka.cruisecontrol.model.Replica;
import com.linkedin.kafka.cruisecontrol.model.ReplicaSortFunctionFactory;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.ACCEPT;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.REPLICA_REJECT;
import static com.linkedin.kafka.cruisecontrol.analyzer.goals.ReplicaDistributionAbstractGoal.ChangeType.*;
import static com.linkedin.kafka.cruisecontrol.common.Resource.DISK;


/**
 * SOFT GOAL: Generate replica movement proposals to ensure that the number of replicas on each broker is
 * <ul>
 * <li>Under: (the average number of replicas per broker) * (1 + replica count balance percentage)</li>
 * <li>Above: (the average number of replicas per broker) * Math.max(0, 1 - replica count balance percentage)</li>
 * </ul>
 */
public class ReplicaDistributionGoal extends ReplicaDistributionAbstractGoal {
  private static final Logger LOG = LoggerFactory.getLogger(ReplicaDistributionGoal.class);

  /**
   * Constructor for Replica Distribution Goal.
   */
  public ReplicaDistributionGoal() {
  }

  public ReplicaDistributionGoal(BalancingConstraint balancingConstraint) {
    this();
    _balancingConstraint = balancingConstraint;
  }

  @Override
  int numInterestedReplicas(ClusterModel clusterModel) {
    return clusterModel.numReplicas();
  }

  /**
   * The rebalance threshold for this goal is set by
   * {@link com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig#REPLICA_COUNT_BALANCE_THRESHOLD_CONFIG}
   */
  @Override
  double balancePercentage() {
    return _balancingConstraint.replicaBalancePercentage();
  }

  /**
   * Check whether the given action is acceptable by this goal. An action is acceptable if the number of replicas at
   * (1) the source broker does not go under the allowed limit.
   * (2) the destination broker does not go over the allowed limit.
   *
   * @param action Action to be checked for acceptance.
   * @param clusterModel The state of the cluster.
   * @return {@link ActionAcceptance#ACCEPT} if the action is acceptable by this goal,
   * {@link ActionAcceptance#REPLICA_REJECT} otherwise.
   */
  @Override
  public ActionAcceptance actionAcceptance(BalancingAction action, ClusterModel clusterModel) {
    switch (action.balancingAction()) {
      case INTER_BROKER_REPLICA_SWAP:
      case LEADERSHIP_MOVEMENT:
        return ACCEPT;
      case INTER_BROKER_REPLICA_MOVEMENT:
        Broker sourceBroker = clusterModel.broker(action.sourceBrokerId());
        Broker destinationBroker = clusterModel.broker(action.destinationBrokerId());

        //Check that destination and source would not become unbalanced.
        return (isReplicaCountUnderBalanceUpperLimitAfterChange(destinationBroker, destinationBroker.replicas().size(), ADD)
               && isReplicaCountAboveBalanceLowerLimitAfterChange(sourceBroker, sourceBroker.replicas().size(), REMOVE))
               ? ACCEPT : REPLICA_REJECT;
      default:
        throw new IllegalArgumentException("Unsupported balancing action " + action.balancingAction() + " is provided.");
    }
  }

  @Override
  public ClusterModelStatsComparator clusterModelStatsComparator() {
    return new ReplicaDistributionGoalStatsComparator();
  }

  @Override
  public String name() {
    return ReplicaDistributionGoal.class.getSimpleName();
  }

  /**
   * Initiates replica distribution goal.
   *
   * @param clusterModel The state of the cluster.
   * @param optimizationOptions Options to take into account during optimization.
   */
  @Override
  protected void initGoalState(ClusterModel clusterModel, OptimizationOptions optimizationOptions) {
    super.initGoalState(clusterModel, optimizationOptions);
    clusterModel.trackSortedReplicas(name(),
                                     optimizationOptions.onlyMoveImmigrantReplicas() ? ReplicaSortFunctionFactory.selectImmigrants()
                                                                                     : null,
                                     ReplicaSortFunctionFactory.prioritizeImmigrants(),
                                     ReplicaSortFunctionFactory.sortByMetricGroupValue(DISK.name()));
  }

  /**
   * Update goal state after one round of self-healing / rebalance.
   * @param clusterModel The state of the cluster.
   * @param excludedTopics The topics that should be excluded from the optimization proposal.
   */
  @Override
  protected void updateGoalState(ClusterModel clusterModel, Set<String> excludedTopics)
      throws OptimizationFailureException {
    try {
      super.updateGoalState(clusterModel, excludedTopics);
    } catch (OptimizationFailureException ofe) {
      clusterModel.untrackSortedReplicas(name());
      throw ofe;
    }
    // Clean up memory usage.
    if (_finished) {
      clusterModel.untrackSortedReplicas(name());
    }
  }

  /**
   * Rebalance the given broker without violating the constraints of the current goal and optimized goals.
   *
   * @param broker         Broker to be balanced.
   * @param clusterModel   The state of the cluster.
   * @param optimizedGoals Optimized goals.
   * @param optimizationOptions Options to take into account during optimization -- e.g. excluded topics.
   */
  @Override
  protected void rebalanceForBroker(Broker broker,
                                    ClusterModel clusterModel,
                                    Set<Goal> optimizedGoals,
                                    OptimizationOptions optimizationOptions) {
    LOG.debug("Rebalancing broker {} [limits] lower: {} upper: {}.", broker.id(), _balanceLowerLimit, _balanceUpperLimit);
    int numReplicas = broker.replicas().size();
    boolean requireLessReplicas = broker.isAlive() ? numReplicas > _balanceUpperLimit : numReplicas > 0;
    boolean requireMoreReplicas = broker.isAlive() && numReplicas < _balanceLowerLimit;
    if (broker.isAlive() && !requireMoreReplicas && !requireLessReplicas) {
      // return if the broker is already within the limit.
      return;
    } else if (!clusterModel.newBrokers().isEmpty() && requireMoreReplicas && !broker.isNew()) {
      // return if we have new brokers and the current broker is not a new broker but requires more load.
      return;
    } else if (((!clusterModel.deadBrokers().isEmpty() && broker.isAlive()) || optimizationOptions.onlyMoveImmigrantReplicas())
               && requireLessReplicas && broker.immigrantReplicas().isEmpty()) {
      // return if (1) cluster is in self-healing mode or (2) optimization option requires only moving immigrant replicas,
      // and the broker requires less load but does not have any immigrant replicas.
      return;
    }

    // Update broker ids over the balance limit for logging purposes.
    if (requireLessReplicas && rebalanceByMovingReplicasOut(broker, clusterModel, optimizedGoals, optimizationOptions)) {
      _brokerIdsAboveBalanceUpperLimit.add(broker.id());
      LOG.debug("Failed to sufficiently decrease replica count in broker {} with replica movements. Replicas: {}.",
                broker.id(), broker.replicas().size());
    } else if (requireMoreReplicas && rebalanceByMovingReplicasIn(broker, clusterModel, optimizedGoals, optimizationOptions)) {
      _brokerIdsUnderBalanceLowerLimit.add(broker.id());
      LOG.debug("Failed to sufficiently increase replica count in broker {} with replica movements. Replicas: {}.",
                broker.id(), broker.replicas().size());
    } else {
      LOG.debug("Successfully balanced replica count for broker {} by moving replicas. Replicas: {}",
                broker.id(), broker.replicas().size());
    }
  }

  private boolean rebalanceByMovingReplicasOut(Broker broker,
                                               ClusterModel clusterModel,
                                               Set<Goal> optimizedGoals,
                                               OptimizationOptions optimizationOptions) {
    Set<String> excludedTopics = optimizationOptions.excludedTopics();
    // Get the eligible brokers.
    SortedSet<Broker> candidateBrokers = new TreeSet<>(Comparator.comparingInt((Broker b) -> b.replicas().size()).thenComparingInt(Broker::id));

    candidateBrokers.addAll(_selfHealingDeadBrokersOnly ? clusterModel.aliveBrokers() : clusterModel
        .aliveBrokers()
        .stream()
        .filter(b -> b.replicas().size() < _balanceUpperLimit)
        .collect(Collectors.toSet()));

    // Get the replicas to rebalance. Replicas are sorted from smallest to largest disk usage.
    List<Replica> replicasToMove = broker.trackedSortedReplicas(name()).sortedReplicas();
    // Now let's move things around.
    for (Replica replica : replicasToMove) {
      if (shouldExclude(replica, excludedTopics)) {
        continue;
      }

      Broker b = maybeApplyBalancingAction(clusterModel, replica, candidateBrokers, ActionType.INTER_BROKER_REPLICA_MOVEMENT,
                                           optimizedGoals, optimizationOptions);
      // Only check if we successfully moved something.
      if (b != null) {
        if (broker.replicas().size() <= (broker.isAlive() ? _balanceUpperLimit : 0)) {
          return false;
        }
        // Remove and reinsert the broker so the order is correct.
        candidateBrokers.remove(b);
        if (b.replicas().size() < _balanceUpperLimit || _selfHealingDeadBrokersOnly) {
          candidateBrokers.add(b);
        }
      }
    }
    // All the replicas has been moved away from the broker.
    return !broker.replicas().isEmpty();
  }

  private boolean rebalanceByMovingReplicasIn(Broker broker,
                                              ClusterModel clusterModel,
                                              Set<Goal> optimizedGoals,
                                              OptimizationOptions optimizationOptions) {
    Set<String> excludedTopics = optimizationOptions.excludedTopics();
    PriorityQueue<Broker> eligibleBrokers = new PriorityQueue<>((b1, b2) -> {
      int result = Integer.compare(b2.replicas().size(), b1.replicas().size());
      return result == 0 ? Integer.compare(b1.id(), b2.id()) : result;
    });

    for (Broker aliveBroker : clusterModel.aliveBrokers()) {
      if (aliveBroker.replicas().size() > _balanceLowerLimit) {
        eligibleBrokers.add(aliveBroker);
      }
    }

    List<Broker> candidateBrokers = Collections.singletonList(broker);

    // Stop when no replicas can be moved in anymore.
    while (!eligibleBrokers.isEmpty()) {
      Broker sourceBroker = eligibleBrokers.poll();
      // Get the replicas to rebalance. Replicas are sorted from smallest to largest disk usage.
      List<Replica> replicasToMove = sourceBroker.trackedSortedReplicas(name()).sortedReplicas();
      // If cluster has dead brokers but source broker is alive, then limit moving replica to immigrant replica.
      if (!clusterModel.deadBrokers().isEmpty() && sourceBroker.isAlive()) {
        replicasToMove = replicasToMove.stream().filter(Replica::isImmigrant).collect(Collectors.toList());
      }
      for (Replica replica : replicasToMove) {
        if (shouldExclude(replica, excludedTopics)) {
          continue;
        }
        Broker b = maybeApplyBalancingAction(clusterModel, replica, candidateBrokers, ActionType.INTER_BROKER_REPLICA_MOVEMENT,
                                             optimizedGoals, optimizationOptions);
        // Only need to check status if the action is taken. This will also handle the case that the source broker
        // has nothing to move in. In that case we will never reenqueue that source broker.
        if (b != null) {
          if (broker.replicas().size() >= (broker.isAlive() ? _balanceLowerLimit : 0)) {
            return false;
          }
          // If the source broker has a lower number of replicas than the next broker in the eligible broker in the
          // queue, we reenqueue the source broker and switch to the next broker.
          if (!eligibleBrokers.isEmpty() && sourceBroker.replicas().size() < eligibleBrokers.peek().replicas().size()) {
            eligibleBrokers.add(sourceBroker);
            break;
          }
        }
      }
    }
    return true;
  }

  private class ReplicaDistributionGoalStatsComparator implements ClusterModelStatsComparator {
    private String _reasonForLastNegativeResult;
    @Override
    public int compare(ClusterModelStats stats1, ClusterModelStats stats2) {
      // Standard deviation of number of replicas over brokers in the current must be less than the pre-optimized stats.
      double stDev1 = stats1.replicaStats().get(Statistic.ST_DEV).doubleValue();
      double stDev2 = stats2.replicaStats().get(Statistic.ST_DEV).doubleValue();
      int result = AnalyzerUtils.compare(stDev2, stDev1, AnalyzerUtils.EPSILON);
      if (result < 0) {
        _reasonForLastNegativeResult = String.format("Violated %s. [Std Deviation of Replica Distribution] post-"
                                                         + "optimization:%.3f pre-optimization:%.3f", name(), stDev1, stDev2);
      }
      return result;
    }

    @Override
    public String explainLastComparison() {
      return _reasonForLastNegativeResult;
    }
  }
}
