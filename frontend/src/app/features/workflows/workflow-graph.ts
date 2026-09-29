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

export function executionStages<T extends { id: number; dependsOn: number[] }>(steps: T[]): T[][] {
  const byId = new Map(steps.map((step) => [step.id, step]));
  const stageOf = new Map<number, number>();
  const visiting = new Set<number>();

  const stage = (step: T): number => {
    const known = stageOf.get(step.id);
    if (known !== undefined) {
      return known;
    }
    if (visiting.has(step.id)) {
      return 0;
    }
    visiting.add(step.id);
    const upstream = step.dependsOn
      .map((id) => byId.get(id))
      .filter((candidate): candidate is T => candidate !== undefined);
    const result = upstream.length === 0 ? 0 : 1 + Math.max(...upstream.map(stage));
    visiting.delete(step.id);
    stageOf.set(step.id, result);
    return result;
  };

  const stages: T[][] = [];
  for (const step of steps) {
    (stages[stage(step)] ??= []).push(step);
  }
  return stages.filter((group) => group !== undefined);
}
