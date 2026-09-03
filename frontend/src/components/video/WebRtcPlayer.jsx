import React, { useEffect, useRef, useState } from 'react';
import '../../styles/WebRtcPlayer.css';

/**
 * WebRtcPlayer: Component phát luồng video WebRTC (WHEP) từ MediaMTX với độ trễ siêu thấp (< 0.2s)
 * @param {string} streamPath - Tên kênh camera trên MediaMTX (ví dụ 'cam01')
 * @param {string} host - Địa chỉ MediaMTX (mặc định localhost:8889)
 * @param {boolean} autoPlay - Tự động phát
 * @param {boolean} muted - Tắt tiếng mặc định để browser cho phép autoplay
 * @param {function} onStatusChange - Callback báo trạng thái kết nối
 */
export default function WebRtcPlayer({
  streamPath = 'cam01',
  host = 'localhost:8889',
  autoPlay = true,
  muted = true,
  cameraName = 'Camera 01',
  onStatusChange = null
}) {
  const videoRef = useRef(null);
  const pcRef = useRef(null);
  const [status, setStatus] = useState('connecting'); // 'connecting' | 'live' | 'error' | 'offline'
  const [errorMsg, setErrorMsg] = useState('');
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    let isMounted = true;
    let peerConnection = null;

    async function startWebRTC() {
      setStatus('connecting');
      setErrorMsg('');

      try {
        peerConnection = new RTCPeerConnection({
          iceServers: [
            { urls: 'stun:stun.l.google.com:19302' }
          ]
        });
        pcRef.current = peerConnection;

        // Chỉ yêu cầu nhận Video (không cần Audio) để tránh lỗi lệch Clock rate WebRTC
        peerConnection.addTransceiver('video', { direction: 'recvonly' });

        peerConnection.ontrack = (event) => {
          if (videoRef.current && event.streams && event.streams[0]) {
            videoRef.current.srcObject = event.streams[0];
            if (isMounted) {
              setStatus('live');
              if (onStatusChange) onStatusChange('live');
            }
          }
        };

        peerConnection.onconnectionstatechange = () => {
          if (!isMounted) return;
          const state = peerConnection.connectionState;
          if (state === 'connected') {
            setStatus('live');
          } else if (state === 'disconnected' || state === 'failed') {
            setStatus('offline');
            // Tự động thử kết nối lại sau 3 giây
            setTimeout(() => {
              if (isMounted) setRetryCount((prev) => prev + 1);
            }, 3000);
          }
        };

        // 1. Tạo SDP Offer
        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);

        // 2. Gửi Offer sang MediaMTX WHEP endpoint
        const whepUrl = `http://${host}/${streamPath}/whep`;
        const response = await fetch(whepUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/sdp'
          },
          body: offer.sdp
        });

        if (!response.ok) {
          throw new Error(`MediaMTX trả về mã lỗi: ${response.status} (${response.statusText})`);
        }

        // 3. Nhận SDP Answer từ MediaMTX
        const answerSdp = await response.text();
        await peerConnection.setRemoteDescription({
          type: 'answer',
          sdp: answerSdp
        });

      } catch (err) {
        console.warn(`[WebRTC Player] Lỗi kết nối luồng ${streamPath}:`, err);
        if (isMounted) {
          setStatus('error');
          setErrorMsg(err.message || 'Không thể kết nối luồng WebRTC');
          if (onStatusChange) onStatusChange('error');
          // Tự động thử lại
          setTimeout(() => {
            if (isMounted) setRetryCount((prev) => prev + 1);
          }, 4000);
        }
      }
    }

    startWebRTC();

    return () => {
      isMounted = false;
      if (peerConnection) {
        peerConnection.close();
      }
    };
  }, [streamPath, host, retryCount]);

  return (
    <div className="webrtc-player-wrapper">
      {/* Video Stream Element */}
      <video
        ref={videoRef}
        autoPlay={autoPlay}
        muted={muted}
        playsInline
        className={`webrtc-video-element ${status === 'live' ? 'active' : ''}`}
      />

      {/* Header Info Overlay */}
      <div className="player-header-overlay">
        <div className="camera-label">
          <span className="cam-code-tag">{streamPath.toUpperCase()}</span>
          <span className="cam-title">{cameraName}</span>
        </div>
        <div className="stream-badge-box">
          {status === 'live' && (
            <span className="live-indicator-chip">
              <span className="live-dot" /> LIVE WebRTC
            </span>
          )}
          {status === 'connecting' && (
            <span className="connecting-chip">
              <span className="spinner-dot" /> Đang kết nối...
            </span>
          )}
          {(status === 'error' || status === 'offline') && (
            <span className="offline-chip">Mất tín hiệu</span>
          )}
        </div>
      </div>

      {/* Placeholder / Connecting State */}
      {status !== 'live' && (
        <div className="player-fallback-overlay">
          {status === 'connecting' ? (
            <div className="fallback-content">
              <div className="radar-spinner" />
              <p>Đang kéo luồng WebRTC từ MediaMTX...</p>
              <span className="sub-hint">Đảm bảo App điện thoại đang mở và phát</span>
            </div>
          ) : (
            <div className="fallback-content">
              <div className="offline-icon">📹</div>
              <p>Chưa có tín hiệu từ Camera ({streamPath})</p>
              <span className="error-detail">{errorMsg || 'Đang chờ nguồn phát từ điện thoại...'}</span>
              <button
                type="button"
                className="btn-retry-stream"
                onClick={() => setRetryCount((prev) => prev + 1)}
              >
                🔄 Thử lại ngay
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
