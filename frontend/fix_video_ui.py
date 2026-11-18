import sys

path = r'C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\frontend\src\pages\guard\GuardDashboardPage.jsx'

with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find the error block by locating the unique anchor strings
start_marker = ') : incidentVideoError ? (\n\t\t\t\t\t\t\t\t\t\t<div className="soc-video-empty-box">\n\t\t\t\t\t\t\t\t\t\t\t<AlertTriangle size={36} color="#ef4444" />'
end_marker = '\t\t\t\t\t\t\t\t\t\t</div>'

start_idx = content.find(start_marker)
if start_idx == -1:
    print("ERROR: start_marker not found")
    sys.exit(1)

# Find the end of this block (the closing </div> after the error actions </div>)
# We need the second </div> after start_idx
search_from = start_idx + len(start_marker)
end_idx = content.find(end_marker, search_from)
if end_idx == -1:
    print("ERROR: end_marker not found")
    sys.exit(1)

# Move end_idx to include the full closing tag line
end_idx = end_idx + len(end_marker)

old_block = content[start_idx:end_idx]
print("=== OLD BLOCK ===")
print(repr(old_block[:200]))

new_block = ') : incidentVideoError ? (\n\t\t\t\t\t\t\t\t\t\t<div className="soc-video-empty-box">\n\t\t\t\t\t\t\t\t\t\t\t<VideoOff size={48} color="#94a3b8" strokeWidth={1.5} />\n\t\t\t\t\t\t\t\t\t\t\t<p className="soc-video-unavailable-title">Video không khả dụng</p>\n\t\t\t\t\t\t\t\t\t\t\t<p className="soc-video-unavailable-sub">\n\t\t\t\t\t\t\t\t\t\t\t\tKhông tìm thấy video sự cố tương ứng với thời điểm phát hiện.\n\t\t\t\t\t\t\t\t\t\t\t</p>\n\t\t\t\t\t\t\t\t\t\t</div>'

new_content = content[:start_idx] + new_block + content[end_idx:]

with open(path, 'w', encoding='utf-8') as f:
    f.write(new_content)

print("DONE - replaced successfully")
