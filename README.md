# WebBridge - Minecraft 服务器与网页互通插件

## 📦 版本
v2.1.5

## 🎯 功能简介
- 游戏账号绑定网页（支持 `/mr`、`/molrain`、`/bind` 等命令）
- 网页注册/登录（支持邮箱验证码）
- 服务器信息实时显示（在线玩家、注册用户数）
- 聊天互通（网页 ↔ 游戏）
- 社区系统（发帖/分类/图片上传/赞踩）
- 公告系统（轮播/发布/删除）
- 个人中心（头像裁剪/昵称/简介/密码修改）
- 管理员面板（用户管理/封禁/解封/建议查看）
- 三种主题切换（黑蓝/白黄/粉白）
- 内置H2数据库（无需安装MySQL）

## 📁 项目结构

WebBridge/
├── pom.xml
└── src/
└── main/
├── java/
│ └── com/mcplugin/
│ ├── WebBridgePlugin.java # 插件主类
│ ├── DatabaseManager.java # 数据库管理
│ ├── MailSender.java # 邮件发送
│ ├── ChatSyncListener.java # 聊天同步
│ ├── WebAPIServer.java # Web服务器+API
│ ├── BindCommand.java # 绑定命令
│ ├── UnbindCommand.java # 解绑命令
│ ├── PlayerListener.java # 玩家事件
│ ├── PlayerDataSync.java # 数据同步
│ └── AdminCommand.java # 管理命令
└── resources/
├── plugin.yml
├── config.yml
└── web/
└── default_avatar.png # 默认头像
