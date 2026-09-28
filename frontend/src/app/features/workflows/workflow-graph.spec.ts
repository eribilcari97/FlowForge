import { dependencyCandidates, dependencyKeys, descendantIds } from './workflow-graph';
import { Step } from './workflow.service';

function step(id: number, key: string, dependsOn: number[] = []): Step {
  return {
    id,
    key,
    name: key,
    jobType: 'DELAY',
    config: { duration: 'PT1S' },
    timeoutSeconds: 30,
    maxAttempts: 3,
    retryDelaySeconds: 10,
    dependsOn,
  };
}

const diamond = [
  step(1, 'a'),
  step(2, 'b', [1]),
  step(3, 'c', [1]),
  step(4, 'd', [2, 3]),
  step(5, 'e'),
];

describe('workflow graph helpers', () => {
  it('finds direct and transitive descendants', () => {
    expect([...descendantIds(diamond, 1)].sort()).toEqual([2, 3, 4]);
    expect([...descendantIds(diamond, 3)]).toEqual([4]);
    expect(descendantIds(diamond, 4).size).toBe(0);
  });

  it('never offers the step itself or anything downstream of it as a dependency', () => {
    expect(dependencyCandidates(diamond, 1).map((s) => s.key)).toEqual(['e']);
    expect(dependencyCandidates(diamond, 2).map((s) => s.key)).toEqual(['a', 'c', 'e']);
    expect(dependencyCandidates(diamond, 4).map((s) => s.key)).toEqual(['a', 'b', 'c', 'e']);
  });

  it('names the dependencies of a step by key', () => {
    expect(dependencyKeys(diamond, diamond[3])).toEqual(['b', 'c']);
    expect(dependencyKeys(diamond, diamond[0])).toEqual([]);
  });
});
