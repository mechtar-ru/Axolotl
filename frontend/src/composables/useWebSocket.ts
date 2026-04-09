import { ref, onUnmounted, readonly } from 'vue';

export interface WebSocketCallbacks {
  onProgress: (data: { schemaId: string; nodeId: string; status: string; progress: number; message: string }) => void;
  onResult: (data: { schemaId: string; nodeId: string; result: any }) => void;
  onError: (data: { schemaId: string; nodeId: string; error: string }) => void;
  onComplete: (data: { schemaId: string; totalTime: number; nodesCompleted: number }) => void;
  onMetrics?: (data: { schemaId: string; totalNodes: number; completedNodes: number; elapsedTime: number; nodesPerSecond: number }) => void;
  onLog?: (message: string) => void;
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

  const connect = (schemaId: string, wsCallbacks: WebSocketCallbacks) => {
    try {
      currentSchemaId = schemaId;
      callbacks = wsCallbacks;

      if (ws.value) {
        ws.value.close();
      }

      const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/execution';
      const url = `${WS_URL}?schemaId=${schemaId}`;
      // Предполагаем, что бэкенд на localhost:8080, но в реальности нужно брать из конфига
      // const url = `ws://127.0.0.1:8080/ws/execution?schemaId=${schemaId}`;
      console.log('🔌 Подключение к WebSocket:', url);
      ws.value = new WebSocket(url);

      ws.value.onopen = () => {
        isConnected.value = true;
        reconnectAttempts.value = 0;
        console.log('🔌 WebSocket подключен');
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
            case 'metrics':
              callbacks?.onMetrics?.(data);
              break;
            case 'log':
              callbacks?.onLog?.(JSON.stringify(data));
              break;
            default:
              console.warn('Unknown message type:', data.type);
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
          callbacks?.onLog?.(`Invalid WS payload: ${event.data}`);
        }
      };

      ws.value.onclose = (event) => {
        isConnected.value = false;
        console.log('🔌 WebSocket отключен, код:', event.code, 'причина:', event.reason);
        // Автоматическое переподключение, если не было намеренного закрытия
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
      };
    } catch (error) {
      console.error('Ошибка создания WebSocket:', error);
    }
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
    disconnect,
    isConnected: readonly(isConnected),
  };
}