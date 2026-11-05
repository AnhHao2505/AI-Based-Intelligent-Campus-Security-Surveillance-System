import os

def update_reason_page():
    path = r"c:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\frontend\src\pages\system\ReasonCatalogPage.jsx"
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    
    old_div = '<div className="syscfg-card" style={{ padding: 0, overflowX: "auto" }}>'
    new_div = '<div className="syscfg-card" style={{ padding: 0, overflowX: "auto", background: "var(--theme-bg-surface)" }}>'
    
    old_tbl = '<table className="reason-table" style={{ width: "100%", minWidth: "720px", borderCollapse: "collapse", fontSize: "13px" }}>'
    new_tbl = '<table className="reason-table" style={{ width: "100%", minWidth: "720px", borderCollapse: "collapse", fontSize: "13px", background: "var(--theme-bg-surface)" }}>'
    
    if old_div in content:
        content = content.replace(old_div, new_div)
    if old_tbl in content:
        content = content.replace(old_tbl, new_tbl)
        
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("ReasonCatalogPage updated!")

def update_policy_page():
    path = r"c:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\frontend\src\pages\system\IncidentPolicyPage.jsx"
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
        
    old_div = '<div className="syscfg-card" style={{ padding: 0, overflowX: "auto", marginBottom: "24px" }}>'
    new_div = '<div className="syscfg-card" style={{ padding: 0, overflowX: "auto", marginBottom: "24px", background: "var(--theme-bg-surface)" }}>'
    
    if old_div in content:
        content = content.replace(old_div, new_div)
        
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("IncidentPolicyPage updated!")

update_reason_page()
update_policy_page()

