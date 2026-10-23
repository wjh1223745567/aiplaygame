Autogame 客户端（Windows x64 便携版）
=====================================

一、怎么用
  1. 解压整个文件夹（不要只拿出 start-client.bat，runtime 目录要跟着）。
  2. 双击 start-client.bat 打开窗口。
  3. 顶部填 host 的「服务 IP」和「端口」，点「连接」：
       成功 -> 右侧显示「已连接 ... 工具 15 个」
       失败 -> 对话区会写原因（host 没起 / IP 不通 / 防火墙没放行）
  4. 中间输入框打字，按「发送」或 Ctrl+Enter。
     上方是对话，下方是这一轮 AI 调用了哪些工具、参数、耗时、返回。

二、地址配置
  填一次就会存进同目录的 client.properties，下次打开自动带出：

      host=192.168.0.31
      port=18080
      session=win
      vision=true

  也可以直接把 http://192.168.0.31:18080 粘进「服务 IP」，端口会自动拆出来。
  换机器/换网络后改这里即可，不用改任何代码。

三、连不上时的排查顺序
  1. macOS 那边 host 在跑吗？浏览器打开 http://<IP>:18080/ai/tools 应返回 JSON。
  2. 两个机器在同一局域网？Windows 上 ping <IP> 通吗？
  3. macOS 防火墙是否放行 18080（系统设置 -> 网络 -> 防火墙）。
  4. 命令行自检（cmd 里，在本目录执行）：
        runtime\bin\java.exe -jar client.jar --selftest
     它会打印生效地址、读到的配置文件、工具数量，失败会给出具体异常。

四、包里有什么
  start-client.bat   启动器（优先用 runtime 里的 javaw，无黑色控制台窗口）
  client.jar         客户端本体（含 Jackson 等全部依赖，不需要装 Java）
  runtime\           Adoptium JRE 21（Windows x64），删了就得自己装 Java 21
  client.properties  地址配置
  README.txt         本文件

五、注意
  - 客户端只负责「发话 + 显示」，大模型决策在 host，截图和键鼠操作在 mcpserver（macOS）。
  - 一轮对话可能跑十几秒到几分钟（AI 多轮选工具），等待时按钮会变灰，属正常。
  - 关窗口不会清空会话；想重新开始点右侧「清空」。
