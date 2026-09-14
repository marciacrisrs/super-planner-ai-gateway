# AI Gateway Roadmap

These issues define the server-side responsibilities extracted from `super-planner-app` AI capabilities.

## Principles

- The Gateway interprets, structures and explains; the domain decides and executes.
- The Gateway never writes directly to the Planner database.
- Contracts are provider-independent and versioned.
- Structured output is validated before leaving the Gateway.
- The PlanningEngine remains the source of truth for feasibility and scheduling.
- User approval is required for material changes.

## Planned issues

1. Organize Week — existing issue #1
2. Provider-independent AI Proposal contract — source app #350
3. Gateway security, authentication and limits
4. AI observability and error handling
5. Natural Language → AiProposal — source app #250
6. Contextual Explanation — source app #251
7. Natural Language → PlanningCommand — source app #252
8. Planning Insights — sources app #258, #353, #354
9. Preference Inference — sources app #253, #355
10. Scenario Interpretation — source app #356
11. Next Action Recommendation — source app #357
