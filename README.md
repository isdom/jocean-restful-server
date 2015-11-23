jocean-restful-server
============

jocean's restful serverside utils

2015-11-23: release 0.0.4 版本：
    
    1、优化RegisterImpl, 使用 inbound.pathpattern 校验注册的 flow's path;
    2、记录特定业务流程的完成次数，并在 MBean 中展现出来, 统计 业务逻辑的执行时间区间, 将 flow 执行的TTL统计，按照 endReason 进行区分, adjust ttl's endreason category output format
    3、为 httpinbound 添加 运行时动态添加 HttpServer 's Feature 的特性
    4、利用 RxActions.doPut 实现 respheader.xml 销毁时，取消对应预设头域
    5、实现附加 HttpResponse 的额外头域特性
    6、适配 SpringBeanHolder's new method: allBeanFactory
    7、使用 SpringBeanHolder 新接口 allApplicationContext 遍历 spring ctx
    8、支持获取 QueryString 的全字符串: QueryParam.value 设置为 "" 即可
    9、使用 UnitAgent.allUnit 获取当前存在的 Unit，并注册其中的 Flow 类
    10、将 httpinbound.xml 抽取到 jocean-restful-server
    11、使用 UnitAgent 的侦听机制 动态注册/注销 业务处理类
    12、RegisterImpl 使用 BeanHolder 查询所需的 flow 实例
    13、实现返回 response 的消息体的ContentType可自定义; 通过 OutputReactor.outputAsContentType
    14、RestfulSubscriber 中实现处理携带 application/x-www-form-urlencoded 的 POST 请求的情况
    15、用 Cachedrequest 代替 ArrayList<HttpObject> 实现业务逻辑
    16、实现 HttpTrade 方式下对 multipart 模式的支持
    17、从 jocean-rosa 转移到 jocean-http，增加对 jocean-http 的依赖
    18、支持multipart/form-data中的多条name="key" value形式的表单提交，会作为QueryParam解析
    19、初步实现multipart/form-data方式的json信令+0~N个文件上传功能
    20、interface rename: EventReceiverSource --> EventEngine
    21、OutputReactor新增output(final Object representation, final String
    22、增加JsonViewable注解，用来指定序列化时要写入的字段
    23、使用startWith来判断JSON类型，不做完全匹配校验，增强兼容性
    24、序列化应答bean时，默认使用FastJSON序列化，但是可以配置使用Jackson序列化，Jackson的功能要丰富很多
    25、使用 gradle 构建

2014-08-09： release 0.0.3 版本：
    
    1、Registrar 更改为接口，将实现部分 分离为 RegistrarImpl
  
