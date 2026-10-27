Autogame 识别客户端（Windows x64 便携版）
=========================================

一、怎么用
  1. 解压整个文件夹（不要只拿出 start-client.bat，runtime 目录要跟着）。
  2. 双击 start-client.bat 打开窗口。
  3. 顶部填 host 的「服务 IP」和「端口」，点「连接」：
       成功 -> 状态栏显示「已连接 ... 工具 6 个」，右侧显示当前游戏画面
       失败 -> 记录区会写原因（host 没起 / IP 不通 / 防火墙没放行）
  4. 「工具」下拉选一个（ping / list_windows / capture / ocr / layout / detect），
     「参数」里填 JSON 对象（没有参数就留空），按回车或点「调用 (Enter)」。
     下面那行灰字显示该工具的必填参数与说明；鼠标停在上面能看到完整描述。
     返回的 JSON 原样贴在记录区，失败会标红并给出原因。
  5. 右侧是当前画面：只在连上时和点「看当前画面」时抓一帧，不按秒刷新。

二、地址配置
  填一次就会存进同目录的 client.properties，下次打开自动带出：

      host=192.168.0.31
      port=18080

  也可以直接把 http://192.168.0.31:18080 粘进「服务 IP」，端口会自动拆出来。
  换机器/换网络后改这里即可，不用改任何代码。

三、常用调用示例
  ping                              连通性检查（顺带看服务版本）
  list_windows                      当前能看到的游戏窗口
  capture                           截一张图（返回里给出落盘路径）
  ocr                               全图文字（每段的 text / box / confidence）
  ocr {"region":[0,0,1200,800]}     只识别一块区域
  detect                            区域融合：YOLO 实体 + OCR 文字兜底
  detect {"max_n":20}               限制返回条数

四、连不上时的排查顺序
  1. macOS 那边 host 在跑吗？浏览器打开 http://<IP>:18080/ai/tools 应返回 JSON。
  2. 两个机器在同一局域网？Windows 上 ping <IP> 通吗？
  3. macOS 防火墙是否放行 18080（系统设置 -> 网络 -> 防火墙）。
  4. 命令行自检（cmd 里，在本目录执行）：
        runtime\bin\java.exe -jar client.jar --selftest
        runtime\bin\java.exe -jar client.jar --selftest --tool=detect
     会打印生效地址、读到的配置文件、记录区字体、工具数量；调用工具时还会打印 ok。

五、包里有什么
  start-client.bat   启动器（优先用 runtime 里的 javaw，无黑色控制台窗口）
  client.jar         客户端本体（含 Jackson 等全部依赖，不需要装 Java）
  runtime\           Adoptium JRE 21（Windows x64），删了就得自己装 Java 21
  client.properties  地址配置
  README.txt         本文件

六、注意
  - 客户端只负责「发调用 + 显示结果」：识别在 mcpserver（macOS），转发在 host。
  - ocr / detect 可能要几秒（全图 OCR + 检测），等待时「调用」按钮会变灰，属正常。
  - 画面刷不出来时，标题下那行小字会写原因（游戏没开 / 窗口不在前台）。
