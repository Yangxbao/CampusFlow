# -*- coding: utf-8 -*-
"""压测准备脚本：生成 CSV 用户数据 + 创建压测目标活动（capacity=100，已发布）"""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8080/api/v1"


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body else None
    with urllib.request.urlopen(req, data=data, timeout=20) as resp:
        return json.loads(resp.read().decode())


# 1. 生成 1000 用户 CSV（jmeter0001..jmeter1000，种子账号）
with open("load-test/data/users.csv", "w", encoding="utf-8", newline="") as f:
    f.write("username,password\n")
    for i in range(1, 1001):
        f.write("jmeter%04d,Jm123456\n" % i)
print("CSV 生成完成: 1000 用户")

# 2. 验证压测账号可登录（抽样）
sample = call("POST", "/auth/login", body={"username": "jmeter0001", "password": "Jm123456"})
assert sample["code"] == 0, "压测账号登录失败: " + str(sample)
print("压测账号登录验证通过")

# 3. 组织者创建压测目标活动（capacity=100）并提交，管理员审核发布
org = call("POST", "/auth/login", body={"username": "organizer1", "password": "org123456"})["data"]["token"]
admin = call("POST", "/auth/login", body={"username": "admin", "password": "admin123456"})["data"]["token"]
now = time.time()
from datetime import datetime, timedelta
fmt = lambda t: datetime.fromtimestamp(t).strftime("%Y-%m-%dT%H:%M:%S")
act = call("POST", "/organizer/activities", org, {
    "title": "压测热门活动-" + datetime.now().strftime("%H%M%S"),
    "description": "JMeter 压测目标活动，1000 用户抢 100 名额",
    "category": 1,
    "location": "线上",
    "startTime": fmt(now + 3600 * 2),
    "endTime": fmt(now + 3600 * 4),
    "signupStartTime": fmt(now),
    "signupEndTime": fmt(now + 3600),
    "capacity": 100
})
aid = act["data"]["id"]
call("POST", "/organizer/activities/%s/submit" % aid, org)
call("POST", "/admin/activities/%s/approve" % aid, admin)
print("压测活动已发布: %s" % aid)

with open("load-test/data/target_activity.txt", "w") as f:
    f.write(str(aid))
print("目标活动 ID 写入 load-test/data/target_activity.txt")
