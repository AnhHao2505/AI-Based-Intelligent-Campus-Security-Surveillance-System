import { useState } from 'react';
import { MapPin, Clock, Check, X, Filter, CheckCircle2 } from 'lucide-react';
import { getMyRequests, createAccessRequest } from '../api/mockPersonalApi';
import './RequestAccessPage.css';

export default function RequestAccessPage() {
  const [requests, setRequests] = useState(getMyRequests());
  const [selectedArea, setSelectedArea] = useState('');
  const [reason, setReason] = useState('');
  const [expandedReasonId, setExpandedReasonId] = useState('REQ-03'); // Open by default for REQ-03 as in Figma
  const [successMessage, setSuccessMessage] = useState('');

  const areaOptions = [
    'Data Center',
    'Library Area (extended hours)',
    'Server Room',
    'Lab AI (Lab 402)',
    'Administrative Office',
  ];

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!selectedArea) return;

    const updated = createAccessRequest(selectedArea, reason);
    setRequests(updated);
    setSelectedArea('');
    setReason('');
    setSuccessMessage('Yêu cầu đã được gửi thành công đến Admin để phê duyệt.');
    setTimeout(() => setSuccessMessage(''), 4000);
  };

  const toggleReason = (id) => {
    setExpandedReasonId(expandedReasonId === id ? null : id);
  };

  return (
    <div className="request-access-container">
      {/* Card 1: New Access Request Form */}
      <div className="request-form-card">
        <h2>New Access Request</h2>
        <p className="request-form-subtitle">Requests are reviewed by the Admin before being approved.</p>

        {successMessage && (
          <div style={{ padding: '12px', backgroundColor: '#dcfce7', color: '#166534', borderRadius: '8px', marginBottom: '16px', fontSize: '14px', fontWeight: '500' }}>
            ✓ {successMessage}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="areaSelect">Area</label>
            <div className="form-select-wrapper">
              <select
                id="areaSelect"
                className="form-select"
                value={selectedArea}
                onChange={(e) => setSelectedArea(e.target.value)}
                required
              >
                <option value="">Select an area...</option>
                {areaOptions.map((area) => (
                  <option key={area} value={area}>
                    {area}
                  </option>
                ))}
              </select>
              <Filter size={16} className="form-select-icon" />
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="reasonInput">Reason</label>
            <textarea
              id="reasonInput"
              className="form-textarea"
              placeholder="Explain why you need access to this area..."
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={4}
              required
            />
          </div>

          <div className="form-actions">
            <button type="submit" className="submit-request-btn">
              <span>Submit Request</span>
              <CheckCircle2 size={16} />
            </button>
          </div>
        </form>
      </div>

      {/* Card 2: My Requests List */}
      <div className="my-requests-card">
        <h2>My Requests</h2>

        <div className="requests-table-wrapper">
          <table className="requests-table">
            <thead>
              <tr>
                <th>Area</th>
                <th>Date Submitted</th>
                <th>Status</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((row) => {
                const isRejected = row.status === 'REJECTED';
                const isApproved = row.status === 'APPROVED';
                const isPending = row.status === 'PENDING';
                const isExpanded = expandedReasonId === row.id;

                return (
                  <tbody key={row.id}>
                    <tr className={isRejected && isExpanded ? 'row-rejected-parent' : ''}>
                      <td>
                        <div className="area-cell">
                          <MapPin size={16} color="#64748b" />
                          <span>{row.area}</span>
                        </div>
                      </td>
                      <td>{row.dateSubmitted}</td>
                      <td>
                        {isPending && (
                          <span className="badge-pending">
                            <Clock size={14} />
                            <span>Pending</span>
                          </span>
                        )}
                        {isApproved && (
                          <span className="badge-approved">
                            <Check size={14} />
                            <span>Approved</span>
                          </span>
                        )}
                        {isRejected && (
                          <span className="badge-rejected">
                            <X size={14} />
                            <span>Rejected</span>
                          </span>
                        )}
                      </td>
                      <td>
                        {isRejected ? (
                          <button
                            type="button"
                            className="view-reason-btn"
                            onClick={() => toggleReason(row.id)}
                          >
                            <span>View reason</span>
                            <Filter size={12} />
                          </button>
                        ) : (
                          <span>{row.details}</span>
                        )}
                      </td>
                    </tr>
                    {isRejected && isExpanded && row.adminNote && (
                      <tr>
                        <td colSpan={4} style={{ padding: '0 20px 16px 20px', borderBottom: '1px solid #f1f5f9' }}>
                          <div className="admin-note-box">
                            <div className="admin-note-header">ADMIN'S NOTE</div>
                            <div className="admin-note-text">{row.adminNote}</div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </tbody>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
