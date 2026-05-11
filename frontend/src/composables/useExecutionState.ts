import { ref, inject, provide, type Ref } from 'vue';

export interface ExecutionState {
  isExecuting: Ref<boolean>;
  progress: Ref<number>;
  elapsedSeconds: Ref<number>;
  totalNodes: Ref<number>;
  completedNodes: Ref<number>;
  logs: Ref<any[]>;
  totalTokens: Ref<number | undefined>;
  estimatedCost: Ref<number | undefined>;
  // New Wave 3 state
  liveData: Ref<Record<string, unknown> | null>;
  stepEvents: Ref<Array<{
    stepIndex: number;
    blockId: string;
    blockType: string;
    label: string;
    status: string;
    details: string;
    duration: number;
    timestamp: number;
  }>>;
}

export const EXECUTION_KEY = Symbol('execution-state');

export function createExecutionState(): ExecutionState {
  return {
    isExecuting: ref(false),
    progress: ref(0),
    elapsedSeconds: ref(0),
    totalNodes: ref(0),
    completedNodes: ref(0),
    logs: ref([]),
    totalTokens: ref(undefined),
    estimatedCost: ref(undefined),
    liveData: ref(null),
    stepEvents: ref([]),
  };
}

export function provideExecutionState(state: ExecutionState) {
  provide(EXECUTION_KEY, state);
}

export function useExecutionState(): ExecutionState | null {
  return inject<ExecutionState | null>(EXECUTION_KEY, null);
}
