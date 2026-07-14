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
  const callbacks = ref<WebSocketCallbacks | null>(null);
  let connectResolve: (() => void) | null = null;
  let connectReject: ((reason: string) => void) | null = null;
  let heartbeatInterval: number | null = null;
  let heartbeatTimeout: number | null = null;
  let lastPongTime = Date.now();
  /** Tracks whether disconnect was deliberate (client-initiated close) */
  let deliberateClose = false;
  /** Generation counter to detect stale callbacks from previous connection */
  let connectionGeneration = 0;

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

  /** Validate incoming WebSocket message structure */
  function validateMessage(data: unknown): { valid: boolean; type?: string; payload?: unknown; error?: string } {
    if (data === null || typeof data !== 'object') {
      return { valid: false, error: 'Message must be an object' };
    }
    const msg = data as Record<string, unknown>;
    if (typeof msg.type !== 'string' || !msg.type) {
      return { valid: false, error: 'Missing or invalid type field' };
    }
    
    // Validate known message types
    const validTypes = new Set([
      'progress', 'result', 'error', 'complete', 'paused', 'metrics',
      'nodeTime', 'log', 'token', 'reasoning', 'wave', 'toolCall',
      'predictCall', 'iteration', 'trajectoryComplete', 'step',
      'live_update', 'deps_needed', 'diffs_needed', 'state_replay', 'pong'
    ]);
    
    if (!validTypes.has(msg.type)) {
      return { valid: false, error: `Unknown message type: ${msg.type}` };
    }
    
    // Per-type required fields validation
    const requiredFields: Record<string, string[]> = {
      progress: ['schemaId', 'nodeId', 'status', 'progress', 'message'],
      result: ['schemaId', 'nodeId', 'result'],
      error: ['schemaId', 'nodeId', 'error'],
      complete: ['schemaId', 'totalTime', 'nodesCompleted'],
      paused: ['schemaId', 'completedNodes', 'totalNodes'],
      metrics: ['schemaId', 'totalNodes', 'completedNodes', 'elapsedTime', 'nodesPerSecond'],
      nodeTime: ['schemaId', 'nodeId', 'durationMs'],
      token: ['schemaId', 'nodeId', 'token'],
      reasoning: ['schemaId', 'nodeId', 'reasoning'],
      wave: ['schemaId', 'waveNumber', 'nodeIds', 'status'],
      toolCall: ['schemaId', 'nodeId', 'toolName', 'args', 'durationMs', 'success'],
      predictCall: ['schemaId', 'nodeId', 'signature', 'inputSummary', 'outputSummary', 'durationMs', 'tokens'],
      iteration: ['schemaId', 'nodeId', 'iteration', 'durationMs', 'toolCalls', 'predictCalls'],
      trajectoryComplete: ['schemaId', 'nodeId', 'totalIterations', 'totalTimeMs', 'totalToolCalls', 'totalPredictCalls', 'totalTokens', 'estimatedCost'],
      step: ['schemaId', 'stepIndex', 'blockId', 'blockType', 'label', 'status', 'details', 'duration'],
      live_update: ['schemaId', 'appType', 'payload'],
      deps_needed: ['schemaId', 'nodeId', 'missing', 'projectPath'],
      diffs_needed: ['schemaId', 'nodeId', 'diffs'],
      state_replay: ['schemaId', 'eventCount'],
    };
    
    const required = requiredFields[msg.type];
    if (required) {
      for (const field of required) {
        if (!(field in msg)) {
          return { valid: false, error: `Missing required field '${field}' for type '${msg.type}'` };
        }
      }
    }
    
    return { valid: true, type: msg.type, payload: msg.payload ?? msg.data };
  }

  function tryReconnect(schemaId: string) {
    if (reconnectTimeout !== null) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }
    if (reconnectAttempts.value >= maxReconnectAttempts) {
      console.warn('🔌 WebSocket: max reconnect attempts reached');
      return;
    }
    reconnectAttempts.value++;
    const delay = getBackoffDelay(reconnectAttempts.value);
    console.log(`🔌 WebSocket: reconnecting in ${delay}ms (attempt ${reconnectAttempts.value}/${maxReconnectAttempts})`);
    reconnectTimeout = window.setTimeout(() => {
      connectInternal(schemaId);
    }, delay);
  }

  function connectInternal(schemaId: string, wsCallbacks?: WebSocketCallbacks) {
    if (reconnectTimeout !== null) {
      clearTimeout(reconnectTimeout);
      reconnectTimeout = null;
    }
    try {
      currentSchemaId = schemaId;
      // Increment generation to invalidate any pending callbacks from old connection
      connectionGeneration++;
      const thisGeneration = connectionGeneration;
      callbacks.value = wsCallbacks ?? callbacks.value ?? null;
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
        // Ignore if connection was superseded by a newer one
        if (connectionGeneration !== thisGeneration) return;
        
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
          callbacks.value?.onReconnect?.(schemaId);
        }
      };

      ws.value.onmessage = (event) => {
        // Ignore if connection was superseded by a newer one
        if (connectionGeneration !== thisGeneration) return;
        
        let data: unknown;
        try {
          data = JSON.parse(event.data);
        } catch {
          console.warn('[WebSocket] Failed to parse message:', event.data);
          return;
        }
        
        // Validate message structure
        const validation = validateMessage(data);
        if (!validation.valid) {
          console.warn('[WebSocket] Invalid message structure:', data);
          return;
        }
        
        // Handle pong from server
        if (validation.type === 'pong') {
          handlePong();
          return;
        }
        const cb = callbacks.value;
        if (!cb) return;
        try {
          // Always call onLog for all message types
          cb.onLog?.(JSON.stringify(data));
          switch (validation.type) {
            case 'progress':
              cb.onProgress(data as { schemaId: string; nodeId: string; status: string; progress: number; message: string });
              break;
            case 'result':
              cb.onResult(data as { schemaId: string; nodeId: string; result: any });
              break;
            case 'error':
              cb.onError(data as { schemaId: string; nodeId: string; error: string });
              break;
            case 'complete':
              cb.onComplete(data as { schemaId: string; totalTime: number; nodesCompleted: number });
              break;
            case 'paused':
              cb.onPaused?.(data as { schemaId: string; completedNodes: number; totalNodes: number; error: string });
              break;
            case 'metrics':
              cb.onMetrics?.(data as { schemaId: string; totalNodes: number; completedNodes: number; elapsedTime: number; nodesPerSecond: number });
              break;
            case 'nodeTime':
              cb.onNodeTime?.(data as { schemaId: string; nodeId: string; durationMs: number });
              break;
            case 'token':
              cb.onToken?.(data as { schemaId: string; nodeId: string; token: string });
              break;
            case 'reasoning':
              cb.onReasoning?.(data as { schemaId: string; nodeId: string; reasoning: string });
              break;
            case 'wave':
              cb.onWave?.(data as { waveNumber: number; nodeIds: string[]; status: string });
              break;
            case 'toolCall':
              cb.onToolCall?.(data as { schemaId: string; nodeId: string; toolName: string; args: string; durationMs: number; success: boolean; result: string });
              break;
            case 'predictCall':
              cb.onPredictCall?.(data as { schemaId: string; nodeId: string; signature: string; inputSummary: string; outputSummary: string; durationMs: number; tokens: number });
              break;
            case 'iteration':
              cb.onIteration?.(data as { schemaId: string; nodeId: string; iteration: number; durationMs: number; toolCalls: number; predictCalls: number });
              break;
            case 'trajectoryComplete':
              cb.onTrajectoryComplete?.(data as { schemaId: string; nodeId: string; totalIterations: number; totalTimeMs: number; totalToolCalls: number; totalPredictCalls: number; totalTokens: number; estimatedCost: number });
              break;
            case 'step':
              cb.onStep?.(data as { schemaId: string; stepIndex: number; blockId: string; blockType: string; label: string; status: string; details: string; duration: number });
              break;
            case 'live_update':
              cb.onLiveUpdate?.(data as { schemaId: string; appType: string; payload: Record<string, unknown> });
              break;
            case 'deps_needed':
              cb.onDepsNeeded?.(data as { schemaId: string; nodeId: string; missing: string[]; projectPath: string });
              break;
            case 'diffs_needed':
              cb.onDiffsNeeded?.(data as { schemaId: string; nodeId: string; diffs: Array<{ filePath: string; diff: string; originalLength: number; newLength: number }> });
              break;
            case 'state_replay':
              cb.onStateReplay?.(data as { schemaId: string; eventCount: number });
              break;
            }
        } catch (error) {
        console.error('🔌 WebSocket: error handling message', error);
      }
    }

      ws.value.onclose = (event) => {
        isConnected.value = false;
        stopHeartbeat();

        if (deliberateClose) {
          console.log('🔌 WebSocket: deliberate close');
          return;
        }

        console.log('🔌 WebSocket: closed code=' + event.code + ' reason=' + event.reason);
        callbacks.value?.onDisconnect?.();

        // Reconnect on unexpected close (not 1000 normal, not 1005 no status)
        // Also reconnect on 1001 (going away), 1006 (abnormal), 1011 (server error)
        const shouldReconnect = event.code !== 1000 && event.code !== 1005;
        if (shouldReconnect && reconnectAttempts.value < maxReconnectAttempts) {
          tryReconnect(schemaId);
        }
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
          disconnect();
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
    callbacks.value = null;
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