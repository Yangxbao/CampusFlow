# -*- coding: utf-8 -*-
"""CampusFlow 端到端业务链路 API 测试（真实环境：MySQL 3306 + VM Redis/RocketMQ）"""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8080/api/v1"


def as_int(x):
    return int(x) if isinstance(x, str) else x


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def login(username, password):
    r = call("POST", "/auth/login", body={"username": username, "password": password})
    assert r["code"] == 0, "login fail: " + str(r)
    return r["data"]["token"]


results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS" if ok else "FAIL") + " | " + name + (" | " + str(detail) if detail else ""))


# ============ 1. 学生报名主链路 ============
student = login("student01", "stu123456")
student2 = login("student02", "stu123456")
student3 = login("student03", "stu123456")

# 用即将发布的活动？没有——用已发布的热门讲座（容量100）
acts = call("GET", "/activities?pageNum=1&pageSize=10", student)["data"]["records"]
hot = [a for a in acts if "人工智能" in a["title"]][0]
AID = hot["id"]
check("学生视角活动列表", len(acts) >= 2, "total=%d" % len(acts))

r = call("POST", "/activities/%s/registrations" % AID, student)
if r["code"] == 23001:
    # 幂等清理：上一轮 e2e 残留的报名 → 先取消等待释放，再重新报名
    regs0 = call("GET", "/my/registrations", student)["data"]["records"]
    mine0 = [x for x in regs0 if x["activityId"] == AID and x["status"] in (1, 2)]
    if mine0:
        call("DELETE", "/my/registrations/%s" % mine0[0]["id"], student)
        time.sleep(4)
    r = call("POST", "/activities/%s/registrations" % AID, student)
check("报名受理(异步确认中)", r["code"] == 0 and r["data"]["result"] == "PENDING_CONFIRM", r.get("message"))

# 重复报名应被 Lua 拦截
r = call("POST", "/activities/%s/registrations" % AID, student)
check("重复报名被拦截(23001)", r["code"] == 23001, r.get("message"))

# 等待 MQ 消费落库（事务提交后立即发送 + 消费，通常 <3s）
time.sleep(6)
regs = call("GET", "/my/registrations", student)["data"]["records"]
mine = [x for x in regs if x["activityId"] == AID]
check("MQ 异步确认落库(已报名)", mine and mine[0]["status"] == 1, mine[0]["statusDesc"] if mine else None)
REG_ID = mine[0]["id"] if mine else None

# ============ 2. 签到凭证 ============
r = call("GET", "/my/registrations/%s/checkin-token" % REG_ID, student)
# 热门讲座 7 天后开始，不在签到窗口 -> 25004 或成功签发？签发不受窗口限制（凭证签发），核销受窗口限制
# 当前实现：签发凭证无窗口限制；核销有时窗。活动未开始 -> 核销会 25004
token_ok = r["code"] == 0 and r["data"]["qrPayload"]
check("签到凭证签发", bool(token_ok), r.get("message"))

# ============ 3. 取消报名 ============
r = call("DELETE", "/my/registrations/%s" % REG_ID, student)
check("取消报名", r["code"] == 0, r.get("message"))
time.sleep(4)
regs = call("GET", "/my/registrations?status=3", student)["data"]["records"]
check("取消后状态已取消", any(x["id"] == REG_ID for x in regs), "")

# 重复取消幂等
r = call("DELETE", "/my/registrations/%s" % REG_ID, student)
check("重复取消幂等成功", r["code"] == 0, r.get("message"))

# ============ 4. 取消后重新报名（状态回迁） ============
r = call("POST", "/activities/%s/registrations" % AID, student)
check("取消后重新报名", r["code"] == 0 and r["data"]["result"] == "PENDING_CONFIRM", r.get("data"))
time.sleep(8)
regs = call("GET", "/my/registrations", student)["data"]["records"]
mine = [x for x in regs if x["activityId"] == AID and x["status"] == 1]
check("重新报名确认成功(同一行复用)", len(mine) == 1, "count=%d" % len(mine))

# ============ 5. 候补链路：把名额压到 0 ============
# student02 报满剩余名额需要循环报名大量用户——这里仅验证候补 API 语义（有名额时拒绝排队）
r = call("POST", "/activities/%s/waitlists" % AID, student3)
check("有名额时候补被拒(24003)", r["code"] == 24003, r.get("message"))

# ============ 6. 组织者链路 ============
org = login("organizer1", "org123456")
my_acts = call("GET", "/organizer/activities", org)["data"]
check("组织者活动列表", as_int(my_acts["total"]) >= 4, "total=%s" % my_acts["total"])

stats = call("GET", "/organizer/activities/%s/stats" % AID, org)
check("活动统计接口", stats["code"] == 0 and as_int(stats["data"]["confirmedCount"]) >= 1,
      "confirmed=%s stock=%s" % (stats["data"]["confirmedCount"], stats["data"]["remainingStock"]))

regs_page = call("GET", "/organizer/activities/%s/registrations" % AID, org)
check("报名人员查询", regs_page["code"] == 0 and as_int(regs_page["data"]["total"]) >= 1,
      "total=%s" % regs_page["data"]["total"])

# 导出任务
exp = call("POST", "/organizer/activities/%s/exports" % AID, org)
check("发起 CSV 导出", exp["code"] == 0, exp.get("message"))
time.sleep(3)
st = call("GET", "/organizer/exports/%s" % exp["data"]["id"], org)
check("导出任务完成", st["code"] == 0 and st["data"]["status"] == 2, "rows=%s" % st["data"].get("rowCount"))

# ============ 7. 管理员链路 ============
admin = login("admin", "admin123456")
dash = call("GET", "/admin/dashboard", admin)
check("管理员仪表盘", dash["code"] == 0 and as_int(dash["data"]["totalUsers"]) >= 1000,
      "users=%s acts=%s" % (dash["data"]["totalUsers"], dash["data"]["totalActivities"]))

pending = call("GET", "/admin/activities?status=1", admin)["data"]["records"]
if len(pending) == 0:
    # 夹具自足：无待审核活动时，组织者现场创建并提交一个
    import datetime
    now = datetime.datetime.now()
    fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S")
    mk = call("POST", "/organizer/activities", org, {
        "title": "e2e审核演示活动-%s" % now.strftime("%H%M%S"),
        "description": "e2e 夹具", "category": 0, "location": "线上",
        "startTime": fmt(now + datetime.timedelta(hours=3)),
        "endTime": fmt(now + datetime.timedelta(hours=5)),
        "signupStartTime": fmt(now), "signupEndTime": fmt(now + datetime.timedelta(hours=2)),
        "capacity": 30})
    call("POST", "/organizer/activities/%s/submit" % mk["data"]["id"], org)
    time.sleep(1)
    pending = call("GET", "/admin/activities?status=1", admin)["data"]["records"]
check("待审核活动查询", len(pending) >= 1, "count=%d" % len(pending))
if pending:
    r = call("POST", "/admin/activities/%s/approve" % pending[0]["id"], admin)
    check("审核通过(触发预热)", r["code"] == 0, r.get("message"))

msgs = call("GET", "/admin/messages", admin)["data"]
check("本地消息表可视", as_int(msgs["total"]) >= 1, "total=%s" % msgs["total"])

audits = call("GET", "/admin/audits", admin)["data"]
check("操作审计查询", as_int(audits["total"]) >= 1, "total=%s" % audits["total"])

# ============ 8. 权限越权测试 ============
r = call("GET", "/admin/dashboard", student)
check("学生访问管理接口被拒(40301)", r["code"] == 40301, r.get("message"))
r = call("GET", "/admin/dashboard")
check("未登录访问被拒(40101)", r["code"] == 40101, r.get("message"))
r = call("GET", "/organizer/activities", student)
check("学生访问组织者接口被拒(40301)", r["code"] == 40301, r.get("message"))

# ============ 9. 通知 ============
r = call("GET", "/my/notifications", student)["data"]
check("通知列表(报名成功等)", as_int(r["total"]) >= 1, "total=%s" % r["total"])

print("\n===== 结果汇总 =====")
passed = sum(1 for _, ok, _ in results if ok)
print("PASS %d/%d" % (passed, len(results)))
for name, ok, detail in results:
    if not ok:
        print("FAILED: " + name + " | " + str(detail))
