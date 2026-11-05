path = r"c:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\frontend\src\components\layout\Sidebar.jsx"

with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Remove FileText import if present
content = content.replace("\tFileText,\n", "")
content = content.replace("FileText,\n", "")

# 2. Block 1 under isFacilityManager:
block1 = """\t\t\t\t\t\t\t\t\t\t\t<NavLink
\t\t\t\t\t\t\t\t\t\t\t\tto="/admin/reports"
\t\t\t\t\t\t\t\t\t\t\t\tclassName={({ isActive }) =>
\t\t\t\t\t\t\t\t\t\t\t\t\t`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
\t\t\t\t\t\t\t\t\t\t\t\t}
\t\t\t\t\t\t\t\t\t\t\t\ttitle={
\t\t\t\t\t\t\t\t\t\t\t\t\tsidebarCollapsed ? "Báo cáo an ninh" : undefined
\t\t\t\t\t\t\t\t\t\t\t\t}
\t\t\t\t\t\t\t\t\t\t\t>
\t\t\t\t\t\t\t\t\t\t\t\t<FileText size={18} />
\t\t\t\t\t\t\t\t\t\t\t\t<span>Báo cáo an ninh</span>
\t\t\t\t\t\t\t\t\t\t\t</NavLink>"""

# Normalize CRLF / LF for block1 replacement
if block1.replace("\r\n", "\n") in content.replace("\r\n", "\n"):
    print("Found block1 in content")

# We can do regex or simple string replacement handling both CRLF and LF
import re

# Pattern to remove NavLink to /admin/reports
pattern = re.compile(
    r'\r?\n[ \t]*<NavLink\s+to="/admin/reports"[\s\S]*?<\/NavLink>',
    re.MULTILINE
)

matches = pattern.findall(content)
print(f"Found {len(matches)} matches for /admin/reports")

content = pattern.sub("", content)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("Updated Sidebar.jsx successfully!")

