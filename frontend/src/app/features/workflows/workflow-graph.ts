import { Step } from './workflow.service';

export function descendantIds(steps: Step[], stepId: number): Set<number> {
  const found = new Set<number>();
  const toVisit = [stepId];
  while (toVisit.length > 0) {
    const current = toVisit.pop()!;
    for (const step of steps) {
      if (step.dependsOn.includes(current) && !found.has(step.id)) {
        found.add(step.id);
        toVisit.push(step.id);
      }
    }
  }
  return found;
}

export function dependencyCandidates(steps: Step[], stepId: number): Step[] {
  const descendants = descendantIds(steps, stepId);
  return steps.filter((step) => step.id !== stepId && !descendants.has(step.id));
}

export function dependencyKeys(steps: Step[], step: Step): string[] {
  return step.dependsOn
    .map((id) => steps.find((candidate) => candidate.id === id)?.key)
    .filter((key): key is string => key !== undefined);
}
