import { ref, onUnmounted, readonly } from 'vue';

export interface WebSocketCallbacks {
  onProgress: (data: { schemaId: string; nodeId: string; status: string; progress: number; message: string }) => void;
  onResult: (data: { schemaId: string; nodeId: string; result: any }) => void;
  onError: (data: { schemaId: string; nodeId: string; error: string }) => void;
  onComplete: (data: { schemaId: string; totalTime: number; nodesCompleted: number }) => void;
  onPaused?: (data: { schemaId: string; completedNodes: number; totalNodes: number; error: string }) => void;
  onMetrics?: (data: { schemaId: string; totalNodes: number; completedNodes: number; elapsedTime: number; nodesPerSecond: number }) => void;
  onLog?: (message: string) => void;
  onNodeTime?: (data: { schemaId: string; nodeId: string; durationMs: number }) => void;
  onToken?: (data: { schemaId: string; nodeId: string; token: string }) => void;
  onWave?: (data: { waveNumber: number; nodeIds: string[]; status: string }) => void;
  onToolCall?: (data: { schemaId: string; nodeId: string; toolName: string; args: string; durationMs: number; success: boolean; result: string }) => void;
  onPredictCall?: (data: { schemaId: string; nodeId: string; signature: string; inputSummary: string; outputSummary: string; durationMs: number; tokens: number }) => void;
  onIteration?: (data: { schemaId: string; nodeId: string; iteration: number; durationMs: number; toolCalls: number; predictCalls: number }) => void;
  onTrajectoryComplete?: (data: { schemaId: string; nodeId: string; totalIterations: number; totalTimeMs: number; totalToolCalls: number; totalPredictCalls: number; totalTokens: number; estimatedCost: number }) => void;
  // New Wave 3 events
  onStep?: (data: { schemaId: string; stepIndex: number; blockId: string; blockType: string; label: string; status: string; details: string; duration: number }) => void;
  onLiveUpdate?: (data: { schemaId: string; appType: string; payload: Record<string, unknown> }) => void;
}

export function useWebSocket() {
  const ws = ref<WebSocket | null>(null);
  const isConnected = ref(false);
  const reconnectAttempts = ref(0);
  const maxReconnectAttempts = 5;
  const reconnectInterval = 3000;
  let reconnectTimeout: number | null = null;
  let currentSchemaId: string | null = null;
  let callbacks: WebSocketCallbacks | null = null;
  let connectResolve: (() => void) | null = null;
  let connectReject: ((reason: string) => void) | null = null;

  const connect = (schemaId: string, wsCallbacks: WebSocketCallbacks) => {
    try {
      currentSchemaId = schemaId;
      callbacks = wsCallbacks;

      if (ws.value) {
        ws.value.close();
      }

      const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/execution';
      const url = `${WS_URL}?schemaId=${schemaId}`;
      console.log('🔌 Подключение к WebSocket:', url);
      ws.value = new WebSocket(url);

      ws.value.onopen = () => {
        isConnected.value = true;
        reconnectAttempts.value = 0;
        console.log('🔌 WebSocket подключен');
        connectResolve?.();
        connectResolve = null;
        connectReject = null;
      };

      ws.value.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          console.log('📨 WebSocket сообщение:', data);
          callbacks?.onLog?.(JSON.stringify(data));
          switch (data.type) {
            case 'progress':
              callbacks?.onProgress(data);
              break;
            case 'result':
              callbacks?.onResult(data);
              break;
            case 'error':
              callbacks?.onError(data);
              break;
            case 'complete':
              callbacks?.onComplete(data);
              break;
            case 'paused':
              callbacks?.onPaused?.(data);
              break;
            case 'metrics':
              callbacks?.onMetrics?.(data);
              break;
            case 'log':
              callbacks?.onLog?.(JSON.stringify(data));
              break;
            case 'nodeTime':
              callbacks?.onNodeTime?.(data);
              break;
            case 'token':
              callbacks?.onToken?.(data);
              break;
            case 'wave':
              callbacks?.onWave?.(data);
              break;
            case 'toolCall':
              callbacks?.onToolCall?.(data);
              break;
            case 'predictCall':
              callbacks?.onPredictCall?.(data);
              break;
            case 'iteration':
              callbacks?.onIteration?.(data);
              break;
            case 'trajectoryComplete':
              callbacks?.onTrajectoryComplete?.(data);
              break;
            // New Wave 3 events
            case 'step':
              callbacks?.onStep?.(data);
              break;
            case 'live_update':
              callbacks?.onLiveUpdate?.(data);
              break;
            default:
              break;
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };

      ws.value.onclose = (event) => {
        isConnected.value = false;
        console.log('🔌 WebSocket отключен, код:', event.code, 'причина:', event.reason);
        if (event.code !== 1000 && reconnectAttempts.value < maxReconnectAttempts) {
          reconnectTimeout = window.setTimeout(() => {
            reconnectAttempts.value++;
            console.log(`Reconnecting... Attempt ${reconnectAttempts.value}`);
            connect(schemaId, wsCallbacks);
          }, reconnectInterval);
        }
      };

      ws.value.onerror = (error) => {
        console.error('WebSocket ошибка:', error);
        connectReject?.('WebSocket connection failed');
        connectResolve = null;
        connectReject = null;
      };
    } catch (error) {
      console.error('Ошибка создания WebSocket:', error);
      connectReject?.('WebSocket creation failed');
    }
  };

  const connectAsync = (schemaId: string, wsCallbacks: WebSocketCallbacks): Promise<void> => {
    return new Promise((resolve, reject) => {
      connectResolve = resolve;
      connectReject = reject;
      connect(schemaId, wsCallbacks);
      setTimeout(() => {
        if (connectReject) {
          connectReject('WebSocket connection timeout');
          connectResolve = null;
          connectReject = null;
        }
      }, 5000);
    });
  };

  const disconnect = () => {
    currentSchemaId = null;
    callbacks = null;
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }
    if (ws.value) {
      ws.value.close(1000, 'Client disconnect');
      ws.value = null;
    }
    isConnected.value = false;
  };

  onUnmounted(() => {
    disconnect();
  });

  return {
    connect,
    connectAsync,
    disconnect,
    isConnected: readonly(isConnected),
  };
}
