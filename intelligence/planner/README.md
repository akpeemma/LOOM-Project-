# intelligence:planner

Planner + executor: intent routing, plan graphs, step execution.

- Architecture: §2.15
- Disposition: ADAPT
- Status: Stage 2D — planner core (TaskPlan, TaskPlanner rule-based decomposition, PlanValidator LLM gate, PlanFailureAnalyzer, PlanContracts seams, PlanRequest) + executor (TaskPlanExecutor, TaskPlanQueue, PlanCover, PlanStepDispatcher, PlanWiring, LoomPlanRunner) + voice/typed entry and pointer guide (Worker C). Rule-based only; no LLM wired yet. Tests: TaskPlannerTest + PlanValidatorTest + TaskPlanExecutorTest + TaskPlanQueueTest + PlanCoverTest + PlanStepDispatcherTest + PlanPointerGuideTest + PlanPointerGuideAdapterTest (plain main() runners, kotlinc + stdlib + android fakes).
