import { useState } from 'react';
import { MapPin, Check, X, Filter, Calendar } from 'lucide-react';
import { getAccessHistory } from '../api/mockPersonalApi';
import './AccessHistoryPage.css';

export default function AccessHistoryPage() {
  const [historyData] = useState(getAccessHistory());
  const [selectedArea, setSelectedArea] = useState('ALL');
  const [filterToday, setFilterToday] = useState(false);

  const areas = ['ALL', 'Library Area', 'Server Room', 'Lab AI (Lab 402)', 'Main Gate'];

  const filteredHistory = historyData.filter((item) => {
    if (selectedArea !== 'ALL' && item.area !== selectedArea) {
      return false;
    }
    if (filterToday && !item.dateTime.includes('21/05/2026')) {
      return false;
    }
    return true;
  });

  return (
    <div className="access-history-card">
      {/* Header & Filter Controls */}
      <div className="access-history-header">
        <h2>My Access History</h2>

        <div className="access-history-filters">
          <div className="date-picker-btn">
            <Calendar size={15} />
            <span>May 19 – May 25, 2026</span>
          </div>

          <button
            className={`today-btn ${filterToday ? 'active' : ''}`}
            onClick={() => setFilterToday(!filterToday)}
          >
            Today
          </button>

          <div className="area-select-wrapper">
            <select
              className="area-select"
              value={selectedArea}
              onChange={(e) => setSelectedArea(e.target.value)}
            >
              <option value="ALL">All Areas</option>
              {areas.filter((a) => a !== 'ALL').map((area) => (
                <option key={area} value={area}>
                  {area}
                </option>
              ))}
            </select>
            <Filter size={14} className="area-select-icon" />
          </div>
        </div>
      </div>

      {/* Access History Table */}
      <div className="access-table-wrapper">
        <table className="access-table">
          <thead>
            <tr>
              <th>Date & Time</th>
              <th>Area</th>
              <th>Result</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {filteredHistory.map((row) => {
              const isUnauthorized = row.result === 'UNAUTHORIZED';
              return (
                <tr key={row.id} className={isUnauthorized ? 'row-unauthorized' : ''}>
                  <td>{row.dateTime}</td>
                  <td>
                    <div className="area-cell">
                      <MapPin size={16} color="#64748b" />
                      <span>{row.area}</span>
                    </div>
                  </td>
                  <td>
                    {isUnauthorized ? (
                      <span className="badge-unauthorized">
                        <X size={14} />
                        <span>Unauthorized</span>
                      </span>
                    ) : (
                      <span className="badge-valid">
                        <Check size={14} />
                        <span>Valid Access</span>
                      </span>
                    )}
                  </td>
                  <td className={isUnauthorized ? 'note-unauthorized' : ''}>{row.note}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
