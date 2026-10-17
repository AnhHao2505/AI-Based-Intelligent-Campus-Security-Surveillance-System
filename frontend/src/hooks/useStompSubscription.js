import { useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
const BROKER_URL = API_BASE_URL.replace(/^http/, 'ws') + '/ws-security';

// Một kết nối STOMP dùng chung cho cả tab: mọi nơi gọi hook chia sẻ client này, không mở kết nối thứ hai.
// Client tạo khi có người subscribe đầu tiên, đóng khi người cuối cùng huỷ (vd. đăng xuất -> AppLayout unmount).
const listeners = new Set();
let sharedClient = null;

function subscribeListener(listener) {
  listener.subscription = sharedClient.subscribe(listener.destination, (message) => {
    if (!message.body) return;
    try {
      listener.onMessage(JSON.parse(message.body));
    } catch (err) {
      console.error('Không đọc được tin nhắn STOMP:', err);
    }
  });
}

function ensureClient() {
  if (sharedClient) return sharedClient;
  const client = new Client({
    brokerURL: BROKER_URL,
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    // Đọc token mỗi lần (kết nối lại) để không dùng token cũ
    beforeConnect: () => {
      const token = localStorage.getItem('accessToken');
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },
    // Kết nối lại thì subscription cũ đã mất -> subscribe lại toàn bộ
    onConnect: () => {
      listeners.forEach(subscribeListener);
    },
  });
  sharedClient = client;
  client.activate();
  return client;
}

/**
 * Subscribe một topic STOMP (/ws-security) trong vòng đời component, dùng chung một kết nối.
 *
 * @param {string|null} destination - topic cần nghe; null/rỗng thì không subscribe
 * @param {(payload: any) => void} onMessage - nhận payload đã JSON.parse
 */
export function useStompSubscription(destination, onMessage) {
  const handlerRef = useRef(onMessage);

  useEffect(() => {
    handlerRef.current = onMessage;
  }, [onMessage]);

  useEffect(() => {
    if (!destination) return undefined;

    const listener = {
      destination,
      onMessage: (payload) => handlerRef.current?.(payload),
      subscription: null,
    };
    listeners.add(listener);
    if (ensureClient().connected) {
      subscribeListener(listener);
    }

    return () => {
      listeners.delete(listener);
      try {
        listener.subscription?.unsubscribe();
      } catch {
        // mất kết nối thì subscription đã mất theo
      }
      if (listeners.size === 0 && sharedClient) {
        const client = sharedClient;
        sharedClient = null;
        client.deactivate();
      }
    };
  }, [destination]);
}
