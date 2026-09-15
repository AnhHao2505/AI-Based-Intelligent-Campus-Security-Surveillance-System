import React from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import './Pagination.css';

/**
 * Standard Pagination component
 * Internal state is STRICTLY 0-indexed to match Spring Boot backend conventions.
 * 
 * @param {Object} props
 * @param {number} props.currentPage - 0-indexed current page (0, 1, 2, ...)
 * @param {number} props.totalPages - Total number of pages
 * @param {number} props.totalElements - Total number of items
 * @param {number} props.pageSize - Number of items per page
 * @param {(page: number) => void} props.onPageChange - Callback with 0-indexed new page number
 * @param {string} [props.itemLabel='bản ghi'] - Label for items (e.g. "khu vực", "camera", "tài khoản")
 * @param {string} [props.className='']
 */
export default function Pagination({
  currentPage = 0,
  totalPages = 1,
  totalElements = 0,
  pageSize = 10,
  onPageChange,
  itemLabel = 'bản ghi',
  className = '',
}) {
  if (totalElements <= 0 && totalPages <= 1) {
    return null;
  }

  // Calculate range X–Y of Z
  const fromItem = totalElements === 0 ? 0 : currentPage * pageSize + 1;
  const toItem = Math.min((currentPage + 1) * pageSize, totalElements);

  // Generate page numbers with ellipses
  const getPageNumbers = () => {
    const pages = [];
    const maxVisible = 7;

    if (totalPages <= maxVisible) {
      for (let i = 0; i < totalPages; i++) {
        pages.push(i);
      }
    } else {
      // Always show first page
      pages.push(0);

      const leftThreshold = 3;
      const rightThreshold = totalPages - 4;

      if (currentPage <= leftThreshold) {
        // Near beginning
        pages.push(1, 2, 3, 4, 'ellipsis-right', totalPages - 1);
      } else if (currentPage >= rightThreshold) {
        // Near end
        pages.push(
          'ellipsis-left',
          totalPages - 5,
          totalPages - 4,
          totalPages - 3,
          totalPages - 2,
          totalPages - 1
        );
      } else {
        // In the middle
        pages.push(
          'ellipsis-left',
          currentPage - 1,
          currentPage,
          currentPage + 1,
          'ellipsis-right',
          totalPages - 1
        );
      }
    }

    return pages;
  };

  const handlePageClick = (pageIndex) => {
    if (pageIndex < 0 || pageIndex >= totalPages || pageIndex === currentPage) return;
    onPageChange?.(pageIndex);
  };

  return (
    <div className={`ui-pagination ${className}`.trim()}>
      {/* Left Info: "Hiển thị X–Y trên tổng số Z {itemLabel}" */}
      <div className="ui-pagination__info">
        Hiển thị <strong>{fromItem}</strong>–<strong>{toItem}</strong> trên tổng số <strong>{totalElements}</strong> {itemLabel}
      </div>

      {/* Right Controls: Prev, Pages, Next */}
      <div className="ui-pagination__controls">
        <button
          type="button"
          className="ui-pagination__btn ui-pagination__btn--nav"
          onClick={() => handlePageClick(currentPage - 1)}
          disabled={currentPage <= 0}
          aria-label="Trang trước"
          title="Trang trước"
        >
          <ChevronLeft size={16} />
        </button>

        <div className="ui-pagination__pages">
          {getPageNumbers().map((item, idx) => {
            if (item === 'ellipsis-left' || item === 'ellipsis-right') {
              return (
                <span key={`ellipsis-${idx}`} className="ui-pagination__ellipsis">
                  &hellip;
                </span>
              );
            }

            const pageIndex = item;
            const isCurrent = pageIndex === currentPage;

            return (
              <button
                key={`page-${pageIndex}`}
                type="button"
                className={`ui-pagination__btn ui-pagination__btn--page ${isCurrent ? 'ui-pagination__btn--active' : ''}`}
                onClick={() => handlePageClick(pageIndex)}
                aria-current={isCurrent ? 'page' : undefined}
                aria-label={`Trang ${pageIndex + 1}`}
              >
                {/* Display 1-indexed to user */}
                {pageIndex + 1}
              </button>
            );
          })}
        </div>

        <button
          type="button"
          className="ui-pagination__btn ui-pagination__btn--nav"
          onClick={() => handlePageClick(currentPage + 1)}
          disabled={currentPage >= totalPages - 1}
          aria-label="Trang tiếp theo"
          title="Trang tiếp theo"
        >
          <ChevronRight size={16} />
        </button>
      </div>
    </div>
  );
}
