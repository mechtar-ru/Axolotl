import { inject, provide, type Ref } from 'vue';

export interface ExecutionState {
  isExecuting: Ref<boolean>;
  progress: Ref<number>;
  elapsedSeconds: Ref<number>;
  totalNodes: Ref<number>;
  completedNodes: Ref<number>;
  logs: Ref<any[]>;
  totalTokens: Ref<number | undefined>;
  estimatedCost: Ref<number | undefined>;
}

export const EXECUTION_KEY = Symbol('execution-state');

export function provideExecutionState(state: ExecutionState) {
  provide(EXECUTION_KEY, state);
}

export function useExecutionState(): ExecutionState | null {
  return inject<ExecutionState | null>(EXECUTION_KEY, null);
}
