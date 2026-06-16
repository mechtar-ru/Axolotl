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
  onReasoning?: (data: { schemaId: string; nodeId: string; reasoning: string }) => void;
  onWave?: (data: { waveNumber: number; nodeIds: string[]; status: string }) => void;
  onToolCall?: (data: { schemaId: string; nodeId: string; toolName: string; args: string; durationMs: number; success: boolean; result: string }) => void;
  onPredictCall?: (data: { schemaId: string; nodeId: string; signature: string; inputSummary: string; outputSummary: string; durationMs: number; tokens: number }) => void;
  onIteration?: (data: { schemaId: string; nodeId: string; iteration: number; durationMs: number; toolCalls: number; predictCalls: number }) => void;
  onTrajectoryComplete?: (data: { schemaId: string; nodeId: string; totalIterations: number; totalTimeMs: number; totalToolCalls: number; totalPredictCalls: number; totalTokens: number; estimatedCost: number }) => void;
  onStep?: (data: { schemaId: string; stepIndex: number; blockId: string; blockType: string; label: string; status: string; details: string; duration: number }) => void;
  onLiveUpdate?: (data: { schemaId: string; appType: string; payload: Record<string, unknown> }) => void;
  onDepsNeeded?: (data: { schemaId: string; nodeId: string; missing: string[]; projectPath: string }) => void;
  onDiffsNeeded?: (data: { schemaId: string; nodeId: string; diffs: Array<{ filePath: string; diff: string; originalLength: number; newLength: number }> }) => void;
  /** Called on unexpected WS disconnect. Implementations should refresh schema + execution state. */
  onDisconnect?: () => void;
  /** Called after successful reconnection. Implementation should re-query schema/execution state. */
  onReconnect?: (schemaId: string) => void;
  /** Called when the server replays buffered events after WS reconnect. */
  onStateReplay?: (data: { schemaId: string; eventCount: number }) => void;
}

const BACKOFF_BASE_MS = 1000;
const MAX_BACKOFF_MS = 16000;
const HEARTBEAT_INTERVAL_MS = 30000;
const HEARTBEAT_TIMEOUT_MS = 8000;

export function useWebSocket() {
  const ws = ref<WebSocket | null>(null);
  const isConnected = ref(false);
  const reconnectAttempts = ref(0);
  const maxReconnectAttempts = 5;
  let reconnectTimeout: number | null = null;
  let currentSchemaId: string | null = null;
  let callbacks: WebSocketCallbacks | null = null;
  let connectResolve: (() => void) | null = null;
  let connectReject: ((reason: string) => void) | null = null;
  let heartbeatInterval: number | null = null;
  let heartbeatTimeout: number | null = null;
  let lastPongTime = Date.now();
  /** Tracks whether disconnect was deliberate (client-initiated close) */
  let deliberateClose = false;

  function getBackoffDelay(attempt: number): number {
    const delay = BACKOFF_BASE_MS * Math.pow(2, attempt - 1);
    return Math.min(delay, MAX_BACKOFF_MS);
  }

  function startHeartbeat() {
    stopHeartbeat();
    heartbeatInterval = window.setInterval(() => {
      if (ws.value && ws.value.readyState === WebSocket.OPEN) {
        ws.value.send(JSON.stringify({ type: 'ping' }));
        heartbeatTimeout = window.setTimeout(() => {
          // No pong received — connection is stale, close to trigger reconnect
          ws.value?.close(4001, 'Heartbeat timeout');
        }, HEARTBEAT_TIMEOUT_MS);
      }
    }, HEARTBEAT_INTERVAL_MS);
  }

  function stopHeartbeat() {
    if (heartbeatInterval !== null) {
      clearInterval(heartbeatInterval);
      heartbeatInterval = null;
    }
    if (heartbeatTimeout !== null) {
      clearTimeout(heartbeatTimeout);
      heartbeatTimeout = null;
    }
  }

  function handlePong() {
    if (heartbeatTimeout !== null) {
      clearTimeout(heartbeatTimeout);
      heartbeatTimeout = null;
    }
  }

  function tryReconnect(schemaId: string, wsCallbacks: WebSocketCallbacks) {
    if (reconnectAttempts.value >= maxReconnectAttempts) {
      console.warn('🔌 WebSocket: max reconnect attempts reached');
      return;
    }
    reconnectAttempts.value++;
    const delay = getBackoffDelay(reconnectAttempts.value);
    console.log(`🔌 WebSocket: reconnecting in ${delay}ms (attempt ${reconnectAttempts.value}/${maxReconnectAttempts})`);
    reconnectTimeout = window.setTimeout(() => {
      connectInternal(schemaId, wsCallbacks);
    }, delay);
  }

  function connectInternal(schemaId: string, wsCallbacks: WebSocketCallbacks) {
    try {
      currentSchemaId = schemaId;
      callbacks = wsCallbacks;
      deliberateClose = false;

      if (ws.value) {
        if (ws.value.readyState === WebSocket.OPEN) {
          console.warn('WebSocket already connected, closing first');
        }
        ws.value.close(1000, 'Reconnecting');
        ws.value = null;
      }

      const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8082/ws/execution';
      const url = `${WS_URL}?schemaId=${schemaId}`;
      ws.value = new WebSocket(url);

      ws.value.onopen = () => {
        isConnected.value = true;
        const wasReconnect = reconnectAttempts.value > 0;
        reconnectAttempts.value = 0;
        console.log('🔌 WebSocket connected');
        startHeartbeat();
        connectResolve?.();
        connectResolve = null;
        connectReject = null;
        // On reconnect, notify caller to refresh state
        if (wasReconnect) {
          callbacks?.onReconnect?.(schemaId);
        }
      };

      ws.value.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          // Handle pong from server
          if (data.type === 'pong') {
            handlePong();
            return;
          }
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
            case 'reasoning':
              callbacks?.onReasoning?.(data);
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
            case 'step':
              callbacks?.onStep?.(data);
              break;
            case 'live_update':
              callbacks?.onLiveUpdate?.(data);
              break;
            case 'deps_needed':
              callbacks?.onDepsNeeded?.(data);
              break;
            case 'diffs_needed':
              callbacks?.onDiffsNeeded?.(data);
              break;
            case 'state_replay':
              callbacks?.onStateReplay?.(data);
              break;
            default:
              break;
          }
        } catch (error) {
          console.error('🔌 WebSocket: error parsing message', error);
        }
      };

      ws.value.onclose = (event) => {
        isConnected.value = false;
        stopHeartbeat();

        if (deliberateClose) {
          console.log('🔌 WebSocket: deliberate close');
          return;
        }

        console.log('🔌 WebSocket: closed code=' + event.code + ' reason=' + event.reason);
        callbacks?.onDisconnect?.();

        // Only reconnect on unexpected close (not 1000) if within limits
        if (event.code !== 1000 && reconnectAttempts.value < maxReconnectAttempts) {
          tryReconnect(schemaId, wsCallbacks);
        }
      };

      ws.value.onerror = () => {
        // onerror is always followed by onclose, so we handle reconnect there
        connectReject?.('WebSocket connection failed');
        connectResolve = null;
        connectReject = null;
      };
    } catch (error) {
      console.error('🔌 WebSocket: creation error', error);
      connectReject?.('WebSocket creation failed');
    }
  }

  const connect = (schemaId: string, wsCallbacks: WebSocketCallbacks) => {
    if (isConnected.value) {
      console.warn('Already connected, skipping duplicate connect');
      return;
    }
    connectInternal(schemaId, wsCallbacks);
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
    deliberateClose = true;
    currentSchemaId = null;
    callbacks = null;
    if (reconnectTimeout) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }
    stopHeartbeat();
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
