## Java并发编程常见问题2

### Q7-1：连接池的一些概念

连接池一般对外提供获得连接、归还连接的接口给客户端使用，并暴露最小空闲连接数、最大连接数等可配置参数，在内部则实现连接建立、连接心跳保持、连接管理、空闲连接回收、连接可用性检测等功能。连接池的结构示意图，如下所示：

![image-20200624143140975](.\images\image-20200624143140975.png)

业务项目中经常会用到的连接池，主要是数据库连接池、Redis 连接池和 HTTP 连接池。

**如何鉴别客户端SDK是否基于连接池**

在使用三方客户端进行网络通信时，我们首先要确定客户端 SDK 是否是基于连接池技术实现的。我们知道，TCP 是面向连接的基于字节流的协议：面向连接，意味着连接需要先创建再使用，创建连接的三次握手有一定开销；

基于字节流，意味着字节是发送数据的最小单元，TCP 协议本身无法区分哪几个字节是完整的消息体，也无法感知是否有多个客户端在使用同一个 TCP 连接，TCP 只是一个读写数据的管道。

如果客户端 SDK 没有使用连接池，而直接是 TCP 连接，那么就需要考虑每次建立 TCP 连接的开销，**并且因为 TCP 基于字节流，在多线程的情况下对同一连接进行复用，可能会产生线程安全问题**。

我们先看一下涉及 TCP 连接的客户端 SDK，对外提供 API 的三种方式。在面对各种三方客户端的时候，只有先识别出其属于哪一种，才能理清楚使用方式。大部分的TCP客户端SDK（包括JDK内置的TCP通讯框架）可以分为以下三类API:

- **连接池和连接分离的 API：**有一个 XXXPool 类负责连接池实现，先从其中获得连接XXXConnection，然后用获得的连接进行服务端请求，完成后使用者需要归还连接。通常，XXXPool 是线程安全的，可以并发获取和归还连接，而 XXXConnection 是非线程安全的。对应到连接池的结构示意图中，XXXPool 就是右边连接池那个框，左边的客户端是我们自己的代码。

- **内部带有连接池的 API：**对外提供一个 XXXClient 类，通过这个类可以直接进行服务端请求；这个类内部维护了连接池，SDK 使用者无需考虑连接的获取和归还问题。一般而言，XXXClient 是线程安全的。对应到连接池的结构示意图中，整个 API 就是蓝色框包裹的部分。

- **非连接池的 API：**一般命名为 XXXConnection，以区分其是基于连接池还是单连接的，而不建议命名为 XXXClient 或直接是 XXX。直接连接方式的 API 基于单一连接，每次使用都需要创建和断开连接，性能一般，且通常不是线程安全的。对应到连接池的结构示意图中，这种形式相当于没有右边连接池那个框，客户端直接连接服务端创建连接。

[^注意]: 虽然上面提到了 SDK 一般的命名习惯，但不排除有一些客户端特立独行，因此在使用三方 SDK 时，一定要先查看官方文档了解其最佳实践，或是在类似 Stackoverflow 的网站搜索 XXX threadsafe/singleton 字样看看大家的回复，也可以一层一层往下看源码，直到定位 到原始 Socket 来判断 Socket 和客户端 API 的对应关系。

明确了 SDK 连接池的实现方式后，我们就大概知道了使用 SDK 的最佳实践：

如果是分离方式，那么连接池本身一般是线程安全的，可以复用。每次使用需要从连接池获取连接，使用后归还，归还的工作由使用者负责。

如果是内置连接池，SDK 会负责连接的获取和归还，使用的时候直接复用客户端。

如果 SDK 没有实现连接池（大多数中间件、数据库的客户端 SDK 都会支持连接池），那通常不是线程安全的，而且短连接的方式性能不会很高，使用的时候需要考虑是否自己封装一个连接池。



### Q7-2：Jedis的连接池机制和源码剖析

#### 1.例子

以 Java 中用于操作 Redis 最常见的库 Jedis 为例，从源码角度分析下 Jedis类到底属于哪种类型的 API，直接在多线程环境下复用一个连接会产生什么问题，以及如何用最佳实践来修复这个问题。

首先，向 Redis 初始化 2 组数据，Key=a、Value=1，Key=b、Value=2：

```java
private static JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);

@PostConstruct
public void init() {
    try (Jedis jedis = new Jedis("127.0.0.1", 6379)) {
        Assert.isTrue("OK".equals(jedis.set("a", "1")), "set a = 1 return OK");
        Assert.isTrue("OK".equals(jedis.set("b", "2")), "set b = 2 return OK");
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        jedisPool.close();
    }));
}
```

然后，启动两个线程，共享操作同一个 Jedis 实例，每一个线程循环 1000 次，分别读取Key 为 a 和 b 的 Value，判断是否分别为 1 和 2。运行后，结果很意外。

程序并没有一直输出正确的结果。

#### 2.Jedis源代码剖析

查看Jedis源代码发现，Jedis 继承了 BinaryJedis，BinaryJedis 中保存了单个 Client 的实例，Client最终继承了 Connection，Connection 中保存了单个 Socket 的实例，和 Socket 对应的两个读写流。因此，一个 Jedis 对应一个 Socket 连接。

```java
public class Jedis extends BinaryJedis implements JedisCommands, MultiKeyCommands, AdvancedJedisCommands, ScriptingCommands, BasicCommands, ClusterCommands, SentinelCommands, ModuleCommands {
}

public class BinaryJedis implements BasicCommands, BinaryJedisCommands, MultiKeyBinaryCommands, AdvancedBinaryJedisCommands, BinaryScriptingCommands, Closeable {
    protected Client client;
    protected Transaction transaction;
    protected Pipeline pipeline;
    private final byte[][] dummyArray;

    public BinaryJedis() {
        this.client = null;
        this.transaction = null;
        this.pipeline = null;
        this.dummyArray = new byte[0][];
        this.client = new Client();
    }
    
public class Client extends BinaryClient implements Commands {
}
    
public class BinaryClient extends Connection {
    
}

public class Connection implements Closeable {
    private static final byte[][] EMPTY_ARGS = new byte[0][];
    private String host = "localhost";
    private int port = 6379;
    private Socket socket;
    private RedisOutputStream outputStream;
    private RedisInputStream inputStream;
}
    
```

类图如下：

![image-20200628145944140](images\image-20200628145944140.png)

如下是Connection类的sendCommand方法，用于发送redis指令。

```java
private static void sendCommand(final RedisOutputStream os, final byte[] command, final byte[]... args) 
{ 
    try { 
        os.write(ASTERISK_BYTE); 
        os.writeIntCrLf(args.length + 1);
        os.write(DOLLAR_BYTE);
        os.writeIntCrLf(command.length);
		os.write(command); 
        os.writeCrLf(); 
        for (final byte[] arg : args) {
            os.write(DOLLAR_BYTE); 
            os.writeIntCrLf(arg.length); 
            os.write(arg); 
            os.writeCrLf(); 
        } 
    } catch (IOException e) {
        throw new JedisConnectionException(e); 
    } 
}
```

从上述代码可以看出来，Jedis发送redis指令时是直接操作RedisOutputStream对象写入字节的。我们在多线程环境下复用 Jedis 对象，其实就是在复用 RedisOutputStream。**如果多个线程在执行操作，那么既无法确保整条命令以一个原子操作写入 Socket，也无法确保写入后、读取前没有其他数据写到远端** 。看到这里，我们应该理解了，为啥多线程情况下使用 Jedis 对象操作 Redis 会出现各种奇怪的问题：

- 以上代码，会造成写操作互相干扰，多条redis指令相互穿插，redis服务端会认为这些不是合法的指令，此时redis服务端会关闭客户端连接，导致连接断开；
- 例子代码中，线程1和2先后发送了get a和get b的指令，redis也返回了值1和值2，但是如果线程2先读取了数据1就会出现数据错乱的问题。

#### 3.正确的方法

正确的方法是，使用Jedis框架中的另一个安全类型JedisPool来获得Jedis实例。如下：

```java
private static JedisPool jedisPool = new JedisPool("127.0.0.1", 6379);

@GetMapping("/right1")
public String right() throws InterruptedException {

    new Thread(() -> {
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < 1000; i++) {
                String result = jedis.get("a");
                if (!"1".equals(result)) {
                    log.warn("Expect a to be 1 but found {}", result);
                    return;
                }
            }
            log.info("execute successfully- {}", Thread.currentThread().getName());
        }
    }).start();
    new Thread(() -> {
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < 1000; i++) {
                String result = jedis.get("b");
                if (!"2".equals(result)) {
                    log.warn("Expect b to be 2 but found {}", result);
                    return;
                }
            }
            log.info("execute successfully- {}", Thread.currentThread().getName());
        }
    }).start();
    TimeUnit.SECONDS.sleep(5);
    return "OK";

}
```

以上代码可以保证不会有线程安全问题。此外，我们最好通过shutdownHook方法，在程序退出之前关闭JedisPool.

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    jedisPool.close();
}));
```

JedisPool 的 getResource 方法在拿到 Jedis 对象后，将自己设置为了连接池。连接池JedisPool，继承了 JedisPoolAbstract，而后者继承了抽象类 Pool，Pool 内部维护了Apache Common 的通用池 GenericObjectPool。JedisPool 的连接池就是基于GenericObjectPool 的。

看到这里我们了解了，Jedis 的 API 实现是我们说的三种类型中的第一种，也就是连接池和连接分离的 API，JedisPool 是线程安全的连接池，Jedis 是非线程安全的单一连接。



### Q7-3：使用连接池务必确保复用

1.池复用的一些概念

池一定是用来复用的，否则其使用代价会比每次创建单一对象更大。对连接池来说更是如此，原因如下：

创建连接池的时候很可能一次性创建了多个连接，大多数连接池考虑到性能，会在初始化的时候维护一定数量的最小连接（毕竟初始化连接池的过程一般是一次性的），可以直接使用。如果每次使用连接池都按需创建连接池，那么很可能你只用到一个连接，但是创建了 N 个连接。

连接池一般会有一些管理模块，也就是连接池的结构示意图中的绿色部分。举个例子，大多数的连接池都有闲置超时的概念。连接池会检测连接的闲置时间，定期回收闲置的连接，把活跃连接数降到最低（闲置）连接的配置值，减轻服务端的压力。一般情况

下，闲置连接由独立线程管理，启动了空闲检测的连接池相当于还会启动一个线程。此外，有些连接池还需要独立线程负责连接保活等功能。因此，启动一个连接池相当于启动了 N 个线程。

除了使用代价，连接池不释放，还可能会引起线程泄露。接下来，我就以 Apache HttpClient 为例，和你说说连接池不复用的问题。首先，创建一个 CloseableHttpClient，设置使用PoolingHttpClientConnectionManager 连接池并启用空闲连接驱逐策略，最大空闲时间

为 60 秒，然后使用这个连接来请求一个会返回 OK 字符串的服务端接口：

