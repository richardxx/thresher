package edu.colorado.thresher;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.dataflow.IFDS.ICFGSupergraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.traverse.DFS;

/**
 * symbolic executor that prunes the call graph to *only* the call graph relevant to the current constraints at interprocedural boundaries
 * @author sam
 *
 */
public class PruningSymbolicExecutor extends OptimizedPathSensitiveSymbolicExecutor {

	private final Map<Constraint,Set<CGNode>> reachableCache;
	private final ICFGSupergraph superGraph; 
	private final Logger logger;
	
	public PruningSymbolicExecutor(CallGraph callGraph, ICFGSupergraph superGraph, Logger logger) {
		super(callGraph, logger);
		//this.expandedCG = expandedCallgraph; //ExpandedCallgraph.make(callGraph);
		this.logger = logger;
		this.superGraph = superGraph;
		this.reachableCache = new HashMap<Constraint, Set<CGNode>>();
	}
	
	@Override
	public Iterator<CGNode> getCallers(IPathInfo path, Graph<CGNode> graph) {
		if (!Options.CALLGRAPH_PRUNING) return this.callGraph.getPredNodes(path.getCurrentNode());
		IPathInfo copy = path.deepCopy();
		CGNode node = copy.getCurrentNode();
		
		// first, consider special case for when the caller is a class initializer
		Set<CGNode> preds = Util.iteratorToSet(this.callGraph.getPredNodes(node));
		// TODO: what if there are several modifiers and only one is a clinit?
		if (preds.size() == 1) { //&& preds.iterator().next().getMethod().isClinit()) {
			return preds.iterator();
		}
		// else, caller is not a class init...
		copy.removeAllLocalConstraintsFromQuery(); // eliminate all non-heap constraints (constraints on function params should be only local constraints left)
		Util.Debug("after removing all local" + path);
		if (copy.foundWitness()) return this.callGraph.getPredNodes(node); // no heap constraints left to produce, so can't prune any callers
		
		long start = System.currentTimeMillis();
		// compute set of all CGNode's that might affect our query
		Map<Constraint,Set<CGNode>> queryModMap = copy.getModifiersForQuery();
		// TODO: check for class inits in modifiers?
		Set<CGNode> modifiers = Util.flatten(queryModMap.values());
		Util.Print("getting mods took " + ((System.currentTimeMillis() - start) / 1000));
		
		if (Options.DEBUG) for (CGNode nod : modifiers) Util.Debug("possible modifier " + nod);
				
		//return computeReducedCallerSet(modifiers, node);
		//return computeReducedCallerSet(modifiers, node);
		return computeeReducedCallerSet(queryModMap, preds);
	}
	
	// TODO: can optimize this -- for path constraints, should really have a map from (vars in constraint) -> Set<CGNode>
	private Iterator<CGNode> computeeReducedCallerSet(Map<Constraint,Set<CGNode>> queryModMap, Set<CGNode> callers) {
		for (Map.Entry<Constraint,Set<CGNode>> entry : queryModMap.entrySet()) {
			Set<CGNode> reachable = reachableCache.get(entry.getKey());
			if (reachable == null) {
				reachable = getCallgraphReachable(entry.getValue());
				reachableCache.put(entry.getKey(), reachable);
			}
			callers.retainAll(reachable);
			if (callers.isEmpty()) break;
		}
		return callers.iterator();
	}
	
	private Iterator<CGNode> computeReducedCallerSet(Collection<CGNode> srcs, CGNode snk) {
		Set<CGNode> callers = Util.iteratorToSet(this.callGraph.getPredNodes(snk));
		Util.Print("callers: " + callers.size());
		long start = System.currentTimeMillis();
		Set<CGNode> toPrune = getPruneeable(srcs, callers);
		Util.Print("pruning took " + ((System.currentTimeMillis() - start) / 1000));
		// TODO: this is unecessary (could just modify caller list in method call), but want to be explicit about what's pruned
		for (CGNode pruneMe : toPrune) {
			Util.Debug("pruned " + pruneMe);
			logger.log("prunedCaller");
		}
		callers.removeAll(toPrune);
		return callers.iterator();
	}
	
	boolean superGraphPathExists(CGNode src, CGNode snk) {
		if (!Options.CALLGRAPH_PRUNING) return true;
		return getPruneable(Collections.singleton(src), Collections.singleton(snk)).isEmpty();
	}
	
	/**
	 * @param srcs - seeds for the search
	 * @return set of all CGNode's reachable from srcs
	 */
	public Set<CGNode> getCallgraphReachable(Collection<CGNode> srcs) {
		Set<CGNode> reachable = new HashSet<CGNode>();
		reachable.addAll(DFS.getReachableNodes(callGraph, srcs));
		for (;;) {
	    	boolean progress = false;
	    	Set<CGNode> frontier = new HashSet<CGNode>();
	    	// not all elements of toPrune are directly reachable
	    	for (CGNode src : srcs) {
	    		for (Iterator<CGNode> callerNodes = callGraph.getPredNodes(src); callerNodes.hasNext();) { // for each caller
	    			progress = true;
	    			CGNode caller = callerNodes.next();
	    			frontier.add(caller);
	    			Set<ISSABasicBlock> possibleStartBlocks = new HashSet<ISSABasicBlock>();
	    			IR ir = caller.getIR();
	    			SSACFG cfg = ir.getControlFlowGraph();
	    			for (Iterator<CallSiteReference> sites = callGraph.getPossibleSites(caller, src); sites.hasNext(); ) { // for each context for the caller
	    				CallSiteReference site = sites.next();
	    				ISSABasicBlock[] blks = ir.getBasicBlocksForCall(site);
	    				for (int i = 0; i < blks.length; i++) {
	    					possibleStartBlocks.add(blks[i]);
	    				}
	    			}
	    			Set<CGNode> callees = new HashSet<CGNode>();
	    			Set<ISSABasicBlock> localReachable = DFS.getReachableNodes(cfg, possibleStartBlocks);
	    			for (ISSABasicBlock blk : localReachable) {
	    				if (blk.getLastInstructionIndex() < 0) continue; 
	    				SSAInstruction instr = blk.getLastInstruction();
	    				if (instr != null && instr instanceof SSAInvokeInstruction) {
	    					SSAInvokeInstruction invoke = (SSAInvokeInstruction) instr;
	    					callees.addAll(callGraph.getPossibleTargets(caller, invoke.getCallSite()));
	    				}
	    			}
	    			reachable.addAll(DFS.getReachableNodes(callGraph, callees));
	    		}
	    	}
	    	if (!progress) break;
	    	srcs = frontier;
    	}
		return reachable;
	}
	
	private Set<CGNode> getPruneeable(Collection<CGNode> srcs, Set<CGNode> callers) {
		Set<CGNode> toPrune = Util.deepCopySet(callers);
    	// remove nodes that are directly (via callee's) reachable from srcs from the set toPrune
    	Set<CGNode> directlyReachable = DFS.getReachableNodes(callGraph, srcs);
    	toPrune.removeAll(directlyReachable); 
    	if (toPrune.isEmpty()) return toPrune; // nothing left to prune; we're done

    	for (;;) {
	    	boolean progress = false;
	    	Set<CGNode> frontier = new HashSet<CGNode>();
	    	// not all elements of toPrune are directly reachable
	    	for (CGNode src : srcs) {
	    		for (Iterator<CGNode> callerNodes = callGraph.getPredNodes(src); callerNodes.hasNext();) { // for each caller
	    			progress = true;
	    			CGNode caller = callerNodes.next();
	    			frontier.add(caller);
	    			Set<ISSABasicBlock> possibleStartBlocks = new HashSet<ISSABasicBlock>();
	    			IR ir = caller.getIR();
	    			SSACFG cfg = ir.getControlFlowGraph();
	    			for (Iterator<CallSiteReference> sites = callGraph.getPossibleSites(caller, src); sites.hasNext(); ) { // for each context for the caller
	    				CallSiteReference site = sites.next();
	    				ISSABasicBlock[] blks = ir.getBasicBlocksForCall(site);
	    				for (int i = 0; i < blks.length; i++) {
	    					possibleStartBlocks.add(blks[i]);
	    				}
	    			}
	    			Set<CGNode> callees = new HashSet<CGNode>();
	    			Set<ISSABasicBlock> reachable = DFS.getReachableNodes(cfg, possibleStartBlocks);
	    			for (ISSABasicBlock blk : reachable) {
	    				if (blk.getLastInstructionIndex() < 0) continue; 
	    				SSAInstruction instr = blk.getLastInstruction();
	    				if (instr != null && instr instanceof SSAInvokeInstruction) {
	    					SSAInvokeInstruction invoke = (SSAInvokeInstruction) instr;
	    					callees.addAll(callGraph.getPossibleTargets(caller, invoke.getCallSite()));
	    				}
	    			}
	    			directlyReachable = DFS.getReachableNodes(callGraph, callees);
	    			toPrune.removeAll(directlyReachable); 
	    	    	if (toPrune.isEmpty()) return toPrune; // nothing left to prune; we're done
	    		}
	    	}
	    	if (!progress) break;
	    	srcs = frontier;
    	}
    	return toPrune;
    }
	
	/**
	 * returns the subset of callers that are not reachable from srcs in the supergraph
	 * @param srcs
	 * @param callers
	 * @return
	 */
	private Set<CGNode> getPruneable(Collection<CGNode> srcs, Set<CGNode> callers) {
		Set<CGNode> toPrune = Util.deepCopySet(callers);
    	// remove nodes that are directly (via callee's) reachable from srcs from the set toPrune
    	Set<CGNode> directlyReachable = DFS.getReachableNodes(callGraph, srcs);
    	toPrune.removeAll(directlyReachable); 
    	if (toPrune.isEmpty()) return toPrune; // nothing left to prune; we're done

		Set<BasicBlockInContext<IExplodedBasicBlock>> seen = new HashSet<BasicBlockInContext<IExplodedBasicBlock>>();
    	// not all elements of toPrune are directly reachable
    	for (CGNode src : srcs) {
    		BasicBlockInContext<IExplodedBasicBlock> exits[] = superGraph.getExitsForProcedure(src);
    		Util.Assert(exits.length == 1, "expecting only one exit!");

    		LinkedList<BasicBlockInContext<IExplodedBasicBlock>> toExplore = new LinkedList<BasicBlockInContext<IExplodedBasicBlock>>();
    		// seed toExplore list with blocks from the exit
    		for (Iterator<BasicBlockInContext<IExplodedBasicBlock>> exitSuccs = superGraph.getSuccNodes(exits[0]); exitSuccs.hasNext();) {
    			BasicBlockInContext<IExplodedBasicBlock> next = exitSuccs.next();
    			Util.Assert(superGraph.classifyEdge(exits[0], next) != ICFGSupergraph.CALL_EDGE); // shouldn't be a call edge
    			toPrune.remove(superGraph.getProcOf(next)); // remove the proc we are about to enter from pruned list
				seen.add(next);
				toExplore.add(next);
    		}
    		
    		// search over the remaining parts of the supergraph, removing all calls we see from the prune set
    		while (!toExplore.isEmpty()) {
    			BasicBlockInContext<IExplodedBasicBlock> blk = toExplore.removeFirst();
				for (Iterator<BasicBlockInContext<IExplodedBasicBlock>> succs = superGraph.getSuccNodes(blk); succs.hasNext();) {
					BasicBlockInContext<IExplodedBasicBlock> succ = succs.next();
					if (superGraph.classifyEdge(blk, succ) == ICFGSupergraph.CALL_EDGE) {
						// use callgraph to remove elements from prunable set
						Set<CGNode> reachable = DFS.getReachableNodes(callGraph, Collections.singleton(superGraph.getProcOf(succ)));
				    	toPrune.removeAll(reachable);
				    	if (toPrune.isEmpty()) return toPrune; // nothing left to prune; we're done
					} else if (seen.add(succ)) { // don't add this blk if we've already seen it
						// if we are entering a new procedure, remove it from the toPrune list
						if (superGraph.classifyEdge(blk, succ) == ICFGSupergraph.RETURN_EDGE) toPrune.remove(superGraph.getProcOf(succ));
						toExplore.add(succ); 
					}
				}
    		}
    	}
    	return toPrune;
	 }
	
	/**
	 * @param srcs - list of node's where control flow can start
	 * @param snk - node whose reaching call set we are interested in
	 * @return IntSet corresponding to {@link CGNode} #'s that can reach snk if control flow starts at srcs 
	 */
	/*
	IntSet getReachingCallsFor(Collection<CGNode> srcs, CGNode snk) {
		Util.Print("about to compute reaching calls");
		ReachingCallsAnalysis analysis = new ReachingCallsAnalysis(superGraph, callGraph, srcs);
		TabulationResult<BasicBlockInContext<IExplodedBasicBlock>,CGNode,Integer> result = analysis.analyze();
		Util.Print("done.");
		BasicBlockInContext<IExplodedBasicBlock>[] entry = superGraph.getEntriesForProcedure(snk);
		Util.Assert(entry.length == 1); // not expecting more than one entry for procedure
		return result.getResult(entry[0]);
	}
	
	private Iterator<CGNode> computeReducedCallerSet(Collection<CGNode> srcs, CGNode snk) {
		IntSet reachSet = getReachingCallsFor(srcs, snk);
		List<CGNode> reducedCallers = new LinkedList<CGNode>();
		
		for (Iterator<CGNode> callers = this.callGraph.getPredNodes(snk); callers.hasNext();) {
			CGNode caller = callers.next();
			if (reachSet.contains(callGraph.getNumber(caller))) reducedCallers.add(caller);
			else {
				Util.Debug("pruned caller " + caller);
				logger.log("pruned caller");
			}
		}
		return reducedCallers.iterator();
	}
	
	private Iterator<CGNode> computeReducedCallerSetAgain(Collection<CGNode> srcs, CGNode snk) {
		List<CGNode> reducedCallers = new LinkedList<CGNode>();
		List<BasicBlockInContext<IExplodedBasicBlock>> blks = new LinkedList<BasicBlockInContext<IExplodedBasicBlock>>();
		for (CGNode node : srcs) {
			BasicBlockInContext<IExplodedBasicBlock> blkArr[] = superGraph.getEntriesForProcedure(node);
			for (int i = 0; i < blkArr.length; i++) {
				Util.Debug("entry " + blkArr[i]);
				blks.add(blkArr[i]);

				for (Iterator<BasicBlockInContext<IExplodedBasicBlock>> iter = superGraph.getPredNodes(blkArr[i]); iter.hasNext(); ) {
					Util.Debug("PRED " + iter.next());
				}
			}
		}
		// get all blocks reachable from the producers
		Set<BasicBlockInContext<IExplodedBasicBlock>> reachable = DFS.getReachableNodes(superGraph, blks);		
		
		for (BasicBlockInContext<IExplodedBasicBlock> blk : reachable) {
			Util.Debug("REACHABLE " + blk);
		}
		
		
		
		for (Iterator<CGNode> callers = this.callGraph.getPredNodes(snk); callers.hasNext();) {
			CGNode caller = callers.next();
			boolean pruned = true; 
			BasicBlockInContext<IExplodedBasicBlock> entryArr[] = superGraph.getEntriesForProcedure(caller);
						
			for (int i = 0; i < entryArr.length; i++) {
				CollectionFilter filter = new CollectionFilter(Collections.singleton(entryArr[i]));
				
				MyDFSPathFinder<BasicBlockInContext<IExplodedBasicBlock>> finder = 
						new MyDFSPathFinder<BasicBlockInContext<IExplodedBasicBlock>>(superGraph, blks.iterator(), filter);
				
				if (finder.find() != null) {
				//if (reachable.contains(entryArr[i])) {
					pruned = false;
					break;
				}
			}
			
			if (!pruned) reducedCallers.add(caller);			
			else {
				Util.Debug("pruned caller " + caller);
				logger.log("pruned caller");
			}
		}
		return reducedCallers.iterator();
	}
	
	private Iterator<CGNode> computeReducedCallerSetOld(Set<CGNode> srcs, CGNode snk) {
		Util.Pre(!srcs.isEmpty());
		if (Options.DEBUG) Util.Debug("sink is " + snk);
		Collection<CGNode> snkCollection = new ArrayList<CGNode>(1);
		snkCollection.add(snk);
		// get nodes forward reachable from srcs
		Set<CGNode> srcsFwReachable = DFS.getReachableNodes(expandedCG, srcs);
		
		if (Options.DEBUG) for (CGNode nod : srcsFwReachable) Util.Debug("fw reachable " + nod);
		
		// get pred nodes of snk
		Iterator<CGNode> callers = this.callGraph.getPredNodes(snk);
		List<CGNode> reducedCallers = new LinkedList<CGNode>(); // pruned list of callers
		while (callers.hasNext()) {
			CGNode caller = callers.next();
			Util.Debug("CALLER " + caller);
			if (srcsFwReachable.contains(caller)) reducedCallers.add(caller); // caller reachable from producers
			else Util.Assert(false, "pruned caller " + caller);
			// else, can't reach caller from producers; no sense in considering it
		}
		return reducedCallers.iterator();
	}	
	*/
	
}