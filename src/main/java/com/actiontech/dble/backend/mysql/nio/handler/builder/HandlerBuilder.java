/*
 * Copyright (C) 2016-2018 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.backend.mysql.nio.handler.builder;

import com.actiontech.dble.backend.mysql.nio.handler.query.DMLResponseHandler;
import com.actiontech.dble.backend.mysql.nio.handler.query.impl.MultiNodeMergeHandler;
import com.actiontech.dble.backend.mysql.nio.handler.query.impl.OutputHandler;
import com.actiontech.dble.plan.node.*;
import com.actiontech.dble.route.RouteResultsetNode;
import com.actiontech.dble.server.NonBlockingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class HandlerBuilder {
    private static Logger logger = LoggerFactory.getLogger(HandlerBuilder.class);

    private PlanNode node;
    private NonBlockingSession session;
    private Set<RouteResultsetNode> rrsNodes = new HashSet<>();

    public HandlerBuilder(PlanNode node, NonBlockingSession session) {
        this.node = node;
        this.session = session;
    }

    public void checkRRSs(RouteResultsetNode[] rrssArray) {
        for (RouteResultsetNode rrss : rrssArray) {
            while (rrsNodes.contains(rrss)) {
                rrss.getMultiplexNum().incrementAndGet();
            }
            rrsNodes.add(rrss);
        }
    }

    /**
     * start all leaf handler of children of special handler
     */
    public static void startHandler(DMLResponseHandler handler) throws Exception {
        for (DMLResponseHandler startHandler : handler.getMerges()) {
            MultiNodeMergeHandler mergeHandler = (MultiNodeMergeHandler) startHandler;
            mergeHandler.execute();
        }
    }


    public BaseHandlerBuilder getBuilder(NonBlockingSession nonBlockingSession, PlanNode planNode, boolean isExplain) {
        BaseHandlerBuilder builder = createBuilder(nonBlockingSession, planNode, isExplain);
        builder.build();
        return builder;
    }

    public BaseHandlerBuilder build() throws Exception {
        final long startTime = System.nanoTime();
        BaseHandlerBuilder builder = getBuilder(session, node, false);
        DMLResponseHandler endHandler = builder.getEndHandler();
        OutputHandler fh = new OutputHandler(BaseHandlerBuilder.getSequenceId(), session);
        endHandler.setNextHandler(fh);
        HandlerBuilder.startHandler(fh);
        long endTime = System.nanoTime();
        logger.debug("HandlerBuilder.build cost:" + (endTime - startTime));
        return builder;
    }

    private BaseHandlerBuilder createBuilder(final NonBlockingSession nonBlockingSession, PlanNode planNode, boolean isExplain) {
        PlanNode.PlanNodeType i = planNode.type();
        if (i == PlanNode.PlanNodeType.TABLE) {
            return new TableNodeHandlerBuilder(nonBlockingSession, (TableNode) planNode, this, isExplain);
        } else if (i == PlanNode.PlanNodeType.JOIN) {
            return new JoinNodeHandlerBuilder(nonBlockingSession, (JoinNode) planNode, this, isExplain);
        } else if (i == PlanNode.PlanNodeType.MERGE) {
            return new MergeNodeHandlerBuilder(nonBlockingSession, (MergeNode) planNode, this, isExplain);
        } else if (i == PlanNode.PlanNodeType.QUERY) {
            return new QueryNodeHandlerBuilder(nonBlockingSession, (QueryNode) planNode, this, isExplain);
        } else if (i == PlanNode.PlanNodeType.NONAME) {
            return new NoNameNodeHandlerBuilder(nonBlockingSession, (NoNameNode) planNode, this, isExplain);
        }
        throw new RuntimeException("not supported tree node type:" + planNode.type());
    }

}
