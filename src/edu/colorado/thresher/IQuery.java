package edu.colorado.thresher;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.analysis.pointers.HeapGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;

public interface IQuery { //extends Comparable {

	// empty list means feasible to the visit() method
	public static final List<IQuery> FEASIBLE = Collections.unmodifiableList(new LinkedList<IQuery>());
	// null means infeasible to the visit() method
	public static final List<IQuery> INFEASIBLE = null;
	
	/**
	 * @return true if the query has been sucessfully witnessed, false otherwise
	 */
	public boolean foundWitness();
	
	/**
	 * add a path constraint to the query based on conditional contained in point
	 * @param point - branch point whose conditional will be used in the constraint
	 * @param trueBranchFeasible - true if the true side of the conditional is feasible, false otherwise
	 * @return true if the query is feasible after adding the constraint, false otherwise 
	 */
	public boolean addConstraintFromBranchPoint(IBranchPoint point, boolean trueBranchFeasible);
	
	public IQuery deepCopy();
	
	/**
	 * @return - true if the query has been refuted, false otherwise
	 */
	public boolean isFeasible();
	
	/**
	 * drop parts of the query that are not shared by the current query and other 
	 */
	public void intersect(IQuery other);
	 
	/**
	 * does this query subsume other? 
	 */
	public boolean contains(IQuery other);
	
	/**
	 * does this query contain constraint?
	 */
	public boolean containsConstraint(Constraint constraint);
	
	/**
	 * modify query to simulate execution of non-call instr
	 * @return null if query is infeasible after executing instr, empty list if query is feasible and there are no case splits,
	 * list of paths to explore (in addition to the current path) if current path is feasible and there are case splits 
	 */
	public List<IQuery> visit(SSAInstruction instr, IPathInfo currentPath);
	
	/**
	 * @param - refuted - list of edges that have already been refuted
	 */
	public List<IQuery> visit(SSAInstruction instr, IPathInfo currentPath, Set<PointsToEdge> refuted);
	
	/**
	 * phi's are a special case between we need a variable to tell us which index to pick
	 */
	public List<IQuery> visitPhi(SSAPhiInstruction instr, int phiIndex, IPathInfo currentPath);
	
	/**
	 * enter a callee. note that the CGNode of currentPath should be the callee, NOT the caller
	 * @param caller - the CGNode that the call was made from
	 * @return null if query is infeasible after executing instr, empty list if query is feasible and there are no case splits,
	 * list of paths to explore (in addition to the current path) if current path is feasible and there are case splits 
	 */
	public List<IQuery> enterCall(SSAInvokeInstruction instr, CGNode caller, IPathInfo currentPath);
	
	/**
	 * return from a callee. note that the CGNode of currentPath should be the caller, NOT the callee
	 * @param callee - method we are returning from
	 */
	public List<IQuery> returnFromCall(SSAInvokeInstruction instr, CGNode callee, IPathInfo currentPath);
	
	/**
	 * stale constraints are constraints that refer to locals of a method we are about to exit from
	 * we should mark the path infeasible if the method contains stale constraints, since these constraints can never be produced
	 * @param currentNode - the node we are about to exit (i.e. return to its caller) 
	 * @return - true if query contains stale constraints, false otherwise
	 */
	public boolean containsStaleConstraints(CGNode currentNode);
	
	/**
	 * give up execution and declare witness, if permitted by this query
	 */
	public void declareFakeWitness();
	
	/**
	 * can the call produce or affect any constraints in this query?
	 */
	public boolean isCallRelevant(SSAInvokeInstruction instr, CGNode caller, CGNode callee, CallGraph cg);
	

	/**
	 * rather than entering a call, overapproximate its effect by dropping all of the constraints it can produce 
	 */
	public void dropConstraintsProduceableInCall(SSAInvokeInstruction instr, CGNode caller, CGNode callee);

	
	/**
	 * reflect context-sensitivity of node in query, if applicable
	 * @param node - CGNode we are about to enter for the first time
	 * @return - true if query is feasible after adding constraints, false otherwise
	 */
	public boolean addContextualConstraints(CGNode node, IPathInfo currentPath);
	
	/**
	 * drop constraints containing non-heap values
	 */
	public void removeAllLocalConstraints();
	
	/**
	 * @return set of methods that could produce constraints in the query
	 */
	//public Set<CGNode> getMethodsRelevantToQuery();
	
	/**
	 * @return a map of constraint -> methods modifying constraint for each constraint;
	 */
	public Map<Constraint,Set<CGNode>> getModifiersForQuery();
	
	/**
	 * remove all constraints that could be produced in the loop headed by loopHead
	 */
	public void removeLoopProduceableConstraints(SSACFG.BasicBlock loopHead, CGNode currentNode);

	/**
	 * returns true if any constraints can be produced in the loop headed by loopHead
	 */
	public boolean containsLoopProduceableConstraints(SSACFG.BasicBlock loopHead, CGNode currentNode);

	/**
	 * to be called after exiting class initializers; write default values to all static fields
	 */
	public void intializeStaticFieldsToDefaultValues();
	
	/**
	 * special case for entering a call without being able to do direct parameter binding
	 */
	public void enterCallFromJump(SSAInvokeInstruction instr, CGNode callee, IPathInfo currentPath);
	
	public List<DependencyRule> getWitnessList();
	
	/**
	 * Query that is always refuted (cannot be witnessed) 
	 */
	public class FalseQuery extends AbstractQuery {
		public FalseQuery() {}
		
		@Override
		public boolean containsConstraint(Constraint c) {
			return false;
		}
		
		@Override
		public boolean foundWitness() {
			return false;
		}
		
		@Override 
		public boolean isFeasible() {
			return true;
		}
		
		@Override
		public IQuery deepCopy() {
			return new FalseQuery();
		}
		
		@Override
		public boolean addConstraintFromBranchPoint(IBranchPoint point, boolean trueBranchFeasible) {
			//Util.Unimp("adding constraint from branch point");
			return true;
		}
		
		@Override
		public void intersect(IQuery other) {
			// do nothing; this query has no constraints, so nothing to intersect!
		}
		
		public List<DependencyRule> getWitnessList() { return null; }
		
		@Override
		public int hashCode() {
			return 5;
		}
		
		@Override
		public boolean equals(Object other) {
			if (!(other instanceof FalseQuery)) return false;
			return this.hashCode() == other.hashCode();
		}
		
		//@Override
		public int compareTo(Object other) {
			if (!(other instanceof FalseQuery)) return -1;
			int thisHash = this.hashCode(), otherHash = other.hashCode();
			if (thisHash < otherHash) return -1;
			else if (thisHash > otherHash) return 1;
			return 0;
		}
	}
}