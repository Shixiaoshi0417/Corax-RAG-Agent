# Corax-Shell 脚本指南

## 快速开始

所有操作通过 `shell(cmd)` 工具执行。支持管道、重定向、后台执行。

```bash
# 搜索并输出
corax-search "天气" | head -3 > /dev/out

# 创建记忆
corax-mem-create person,note 张三的生日是5月20日

# 查看配置
cat /proc/sys/temperature
```

## 可用命令

### 内置命令
`ls` `cat` `echo` `grep` `wc` `head` `tail` `date` `sleep`

### Corax 命令
| 命令 | 说明 |
|------|------|
| `corax-search <query>` | 联网搜索 |
| `corax-fetch <url>` | 抓取网页全文 |
| `corax-mem-create [--public] <tags> <content>` | 创建记忆 |
| `corax-mem-rm <id>` | 删除记忆 |
| `corax-mem-tag [--public] <tag>` | 按标签搜索记忆 |
| `corax-mem-search [--public] <keyword>` | 按内容搜索记忆 |
| `corax-skill <name>` | 加载技能 |
| `corax-listen <on|off|status>` | 监听模式 |
| `corax-help` | 查看帮助 |

## 文件系统

| 路径 | 说明 | 权限 |
|------|------|:--:|
| `/proc/sys/` | 系统属性 | api_key ro, 其余 rw |
| `/proc/self/` | 当前会话状态 | ro |
| `/proc/prompt/active` | 当前人设 | ro, 写=切换 |
| `/etc/prompt/*.prompt.txt` | 人设文件 | 激活 ro, 其他 rw |
| `/etc/skills/*.skill.txt` | 技能文件 | rw |
| `/etc/admins.txt` 等 | 名单列表 | rw |
| `/dev/out` | 发送消息 | w |
| `/dev/msg-stream` | 消息总线 | ro (FIFO) |
| `/ctx/` | 上下文历史 | ro |
| `/var/data.db` | 记忆数据库 | SQL |

## 后台守护 (daemon)

```bash
# 启动后台任务
echo 'while true; do sleep 3600; corax-search "热搜" | head > /dev/out; done' > /persist/hourly-news
/persist/hourly-news &

# 查看进程
cat /proc/ps

# 停止
echo 1 > /proc/<pid>/kill
```

## 消息监听示例

```bash
# 每收到消息时检查关键词
cat > /persist/echo-cat << 'EOF'
while true; do
  msg=$(cat /dev/msg-stream)
  echo "$msg" | grep -q "喵" && echo "喵~" > /dev/out
done
EOF
/persist/echo-cat &
```

## 限制

- /tmp/ 最多 50 个文件，单文件 100KB
- /var/data.db 不允许 DROP/ALTER
- api_key 只读不可改
- 当前激活的人设文件不可覆写
