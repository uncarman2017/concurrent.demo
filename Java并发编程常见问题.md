## Java并发编程常见问题

### Q1-1：ConcurrentHashMap的安全性

ConcurrentHashMap是一个高性能的线程安全的哈希表容器

这里的线程安全指的是ConcurrentHashMap提供的原子性读写方法是线程安全的。但并不能保证ConcurrentHashMap存储的数据是准确的。

 示例代码如下：

```java
@RestController
@RequestMapping("concurrenthashmapmisuse")
@Slf4j
public class ConcurrentHashMapMisuseController {

    //线程数量
    private static int THREAD_COUNT = 10;
    // 记录数
    private static int ITEM_COUNT = 1000;

    /**
     * 获得一个指定元素数量模拟数据的ConcurrentHashMap
     * @param count
     * @return
     */
    private ConcurrentHashMap<String, Long> getData(int count) {
        return LongStream.rangeClosed(1, count)
                .boxed()
                .collect(Collectors.toConcurrentMap(i -> UUID.randomUUID().toString(), Function.identity(),
                        (o1, o2) -> o1, ConcurrentHashMap::new));
    }

    @GetMapping("wrong")
    public String wrong() throws InterruptedException {
        // 初始化900个元素
        ConcurrentHashMap<String, Long> concurrentHashMap = getData(ITEM_COUNT - 100);
        log.info("init size:{}", concurrentHashMap.size());

        // 创建一个线程池，并使用线程池并发处理逻辑
        ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
        forkJoinPool.execute(() -> IntStream.rangeClosed(1, 10).parallel().forEach(i -> {
            // 在线程方法中使用size()方法计算剩余要填充的元素
            int gap = ITEM_COUNT - concurrentHashMap.size();
            log.info("gap size:{}", gap);
            //填充剩余元素
            concurrentHashMap.putAll(getData(gap));
        }));
        // 等待全部并行任务完成后关闭线程池
        forkJoinPool.shutdown();
        forkJoinPool.awaitTermination(1, TimeUnit.HOURS);

        log.info("finish size:{}", concurrentHashMap.size());
        return "OK";
    }
}
```

size、isEmpty 和 containsValue 等聚合方法，在并发情况下可能会反映ConcurrentHashMap 的中间状态。因此在并发情况下，这些方法的返回值只能用作参考，而不能用于流程控制。显然，上述代码利用 size 方法计算差异值，是一个流程控制。

putAll 这样的聚合方法也不能确保原子性，在 putAll 的过程中去获取数据可能会获取到部分数据。



### Q1-2：优化高并发下ConcurrentHashMap的读写性能

举例：使用 Map 来统计 Key 出现次数的场景

使用 ConcurrentHashMap 来统计，Key 的范围是 10。使用最多 10 个并发，循环操作 1000 万次，每次操作累加随机的 Key。如果 Key 不存在的话，首次设置值为 1。

示例代码如下：

```java
// 循环次数
private static int LOOP_COUNT = 10000000;
// 线程数量
private static int THREAD_COUNT = 10;
// 记录数
private static int ITEM_COUNT = 1000;

private Map<String, Long> normalUse() throws InterruptedException {
    ConcurrentHashMap<String, Long> freqs = new ConcurrentHashMap<>(ITEM_COUNT);
    ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
    forkJoinPool.execute(() -> IntStream.rangeClosed(1, LOOP_COUNT).parallel().forEach(i -> {
        // 获取一个随机key
        String key = "item" + ThreadLocalRandom.current().nextInt(ITEM_COUNT);
        synchronized (freqs) {
            // 对key进行计数
            if (freqs.containsKey(key)) {
                freqs.put(key, freqs.get(key) + 1);
            } else {
                freqs.put(key, 1L);
            }
        }
    }));
    forkJoinPool.shutdown();
    forkJoinPool.awaitTermination(1, TimeUnit.HOURS);
    return freqs;
}
```

我们吸取之前的教训，直接通过锁的方式锁住 Map，然后做判断、读取现在的累计值、加1、保存累加后值的逻辑。这段代码在功能上没有问题，但无法充分发挥

ConcurrentHashMap 的威力，改进后的代码如下：

```java
private Map<String, Long> goodUse() throws InterruptedException {
    ConcurrentHashMap<String, LongAdder> freqs = new ConcurrentHashMap<>(ITEM_COUNT);
    ForkJoinPool forkJoinPool = new ForkJoinPool(THREAD_COUNT);
    forkJoinPool.execute(() -> IntStream.rangeClosed(1, LOOP_COUNT).parallel().forEach(i -> {
        String key = "item" + ThreadLocalRandom.current().nextInt(ITEM_COUNT);
        freqs.computeIfAbsent(key, k -> new LongAdder()).increment();
    }));
    forkJoinPool.shutdown();
    forkJoinPool.awaitTermination(1, TimeUnit.HOURS);
    return freqs.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(),e -> e.getValue().longValue()));
}
```

在这段改进后的代码中，我们巧妙利用了下面两点：

使用 ConcurrentHashMap 的原子性方法 **computeIfAbsent** 来做复合逻辑操作，判断Key 是否存在 Value，如果不存在则把 Lambda 表达式运行后的结果放入 Map 作为Value，也就是新创建一个 LongAdder 对象，最后返回 Value。

由于 computeIfAbsent 方法返回的 Value 是 LongAdder，是一个线程安全的累加器，因此可以直接调用其 increment 方法进行累加。

执行后的结果比较如下：

```
2020-06-03 17:52:54.320  INFO 17716 --- [nio-8080-exec-6] q.ConcurrentHashMapPerformanceController : StopWatch '': running time = 3519356400 ns
---------------------------------------------
ns         %     Task name
---------------------------------------------
3074245200  087%  normaluse
445111200  013%  gooduse
```

computeIfAbsent 为什么如此高效呢？

答案就在源码最核心的部分，也就是 Java 自带的 Unsafe 实现的 CAS。它在虚拟机层面确保了写入数据的原子性，比加锁的效率高得多。

```java
static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i, Node<K,V> c, Node<K,V> v) { 
    return U.compareAndSetObject(tab, ((long)i << ASHIFT) + ABASE, c, v); 
}
```



### Q2-1：ThreadLocal的安全问题

举例：使用ThreadLocal保存用户信息出现了用户信息读取错乱

ThreadLocal 适用于变量在线程间隔离，而在方法或类间共享的场景。如果用户信息的获取比较昂贵（比如从数据库查询用户信息），那么在 ThreadLocal 中缓存数据是比较合适的做法。

使用 Spring Boot 创建一个 Web 应用程序，使用 ThreadLocal 存放一个 Integer 的值，来暂且代表需要在线程中保存的用户信息，这个值初始是 null。在业务逻辑中，先从ThreadLocal 获取一次值，然后把外部传入的参数设置到 ThreadLocal 中，来模拟从当前上下文获取到用户信息的逻辑，随后再获取一次值，最后输出两次获得的值和线程名称。

```java
private static final ThreadLocal<Integer> currentUser = ThreadLocal.withInitial(() -> null);

@GetMapping("wrong")
public Map wrong(@RequestParam("userId") Integer userId) {
    // 设置用户信息之前先查询一次ThreadLocal中的用户信息
    String before  = Thread.currentThread().getName() + ":" + currentUser.get();
    // 设置用户信息到ThreadLocal
    currentUser.set(userId);
    String after  = Thread.currentThread().getName() + ":" + currentUser.get();
    Map result = new HashMap();
    result.put("before", before);
    result.put("after", after);
    return result;
}
```

```java

```

按理说，在设置用户信息之前第一次获取的值始终应该是 null，但我们要意识到，程序运行在 Tomcat 中，执行程序的线程是 Tomcat 的工作线程，而 Tomcat 的工作线程是基于线程池的。

**顾名思义，线程池会重用固定的几个线程，一旦线程重用，那么很可能首次从ThreadLocal 获取的值是之前其他用户的请求遗留的值。这时，ThreadLocal 中的用户信息就是其他用户的信息。**

因为线程的创建比较昂贵，所以 Web 服务器往往会使用线程池来处理请求，这就意味着线程会被重用。这时，**使用类似 ThreadLocal 工具来存放一些数据时，需要特别注意在****代码运行完后，显式地去清空设置的数据**。如果在代码中使用了自定义的线程池，也同样会遇到这个问题。

改进后的代码如下：

```java
@GetMapping("right")
public Map right(@RequestParam("userId") Integer userId) {
    String before  = Thread.currentThread().getName() + ":" + currentUser.get();
    currentUser.set(userId);
    try {
        String after = Thread.currentThread().getName() + ":" + currentUser.get();
        Map result = new HashMap();
        result.put("before", before);
        result.put("after", after);
        return result;
    } finally {
        currentUser.remove();
    }
}
```



### Q3-1：CopyOnWrite技术使用的误区

CopyOnWrite 是一个时髦的技术，不管是 Linux 还是 Redis 都会用到。**在 Java 中，CopyOnWriteArrayList 虽然是一个线程安全的 ArrayList，但因为其实现方式是，每次修改数据时都会复制一份数据出来，所以有明显的适用场景，即读多写少或者说希望无锁读的场景。**

如果我们要使用 CopyOnWriteArrayList，那一定是因为场景需要而不是因为足够酷炫。如果读写比例均衡或者有大量写操作的话，使用 CopyOnWriteArrayList 的性能会非常糟糕。

如下代码测试并发读写的性能

```java
/**
     * 测试并发写的性能
     * @return
     */
@GetMapping("write")
public Map testWrite() {
    List<Integer> copyOnWriteArrayList = new CopyOnWriteArrayList<>();
    List<Integer> synchronizedList = Collections.synchronizedList(new ArrayList<>());
    StopWatch stopWatch = new StopWatch();
    int loopCount = 100000;
    stopWatch.start("Write:copyOnWriteArrayList");
    // 循环100000次并发往CopyOnWriteArrayList写入随机元素
    IntStream.rangeClosed(1, loopCount).parallel().forEach(__ -> copyOnWriteArrayList.add(ThreadLocalRandom.current().nextInt(loopCount)));
    stopWatch.stop();
    stopWatch.start("Write:synchronizedList");
    // 循环100000次并发往加锁的ArrayList写入随机元素
    IntStream.rangeClosed(1, loopCount).parallel().forEach(__ -> synchronizedList.add(ThreadLocalRandom.current().nextInt(loopCount)));
    stopWatch.stop();
    log.info(stopWatch.prettyPrint());
    Map result = new HashMap();
    result.put("copyOnWriteArrayList", copyOnWriteArrayList.size());
    result.put("synchronizedList", synchronizedList.size());
    return result;
}


/**
     * 测试并发读的性能
     * @return
     */
@GetMapping("read")
public Map testRead() {
    List<Integer> copyOnWriteArrayList = new CopyOnWriteArrayList<>();
    List<Integer> synchronizedList = Collections.synchronizedList(new ArrayList<>());
    addAll(copyOnWriteArrayList);
    addAll(synchronizedList);
    StopWatch stopWatch = new StopWatch();
    int loopCount = 1000000;
    int count = copyOnWriteArrayList.size();
    stopWatch.start("Read:copyOnWriteArrayList");
    IntStream.rangeClosed(1, loopCount).parallel().forEach(__ -> copyOnWriteArrayList.get(ThreadLocalRandom.current().nextInt(count)));
    stopWatch.stop();
    stopWatch.start("Read:synchronizedList");
    IntStream.range(0, loopCount).parallel().forEach(__ -> synchronizedList.get(ThreadLocalRandom.current().nextInt(count)));
    stopWatch.stop();
    log.info(stopWatch.prettyPrint());
    Map result = new HashMap();
    result.put("copyOnWriteArrayList", copyOnWriteArrayList.size());
    result.put("synchronizedList", synchronizedList.size());
    return result;
}

private void addAll(List<Integer> list) {
    list.addAll(IntStream.rangeClosed(1, 1000000).boxed().collect(Collectors.toList()));
}
```

为何在大量写的场景下，CopyOnWriteArrayList 会这么慢呢？

答案就在源码中。以 add 方法为例，每次 add 时，都会用 Arrays.copyOf 创建一个新数组，频繁 add 时内存的申请释放消耗会很大：

```java
public boolean add(E e) { 
    synchronized (lock) { 
        Object[] elements = getArray(); 
        int len = elements.length; 
        Object[] newElements = Arrays.copyOf(elements, len + 1); 
        newElements[len] = e; 13 setArray(newElements); 
        return true; 
    } 
}
```



### Q4-1：加锁的一处误区-volatile

示例：在一个类里有两个 int 类型的字段 a 和 b，有一个 add 方法循环 1 万次对 a 和 b 进行 ++ 操作，有另一个 compare 方法，同样循环 1 万次判断 a是否小于 b，条件成立就打印 a 和 b 的值，并判断 a>b 是否成立。

代码如下：

```java
@Slf4j 
public class Interesting { 
    volatile int a = 1; 
    volatile int b = 1; 
    
    public void add() { 
        log.info("add start"); 
        for (int i = 0; i < 10000; i++) { 
            a++; b++; 
        }
        log.info("add done"); 
    }
    
    public void compare() { 
        log.info("compare start"); 
        for (int i = 0; i < 10000; i++) { 
            //a始终等于b吗？
			if (a < b) { 
                log.info("a:{},b:{},{}", a, b, a > b); 
                //最后的a>b应该始终是false吗？
			} 
        }
        log.info("compare done"); 
    } 
}

```

如下代码启动两个线程，执行add和compare方法

```java
Interesting interesting = new Interesting(); 
new Thread(() -> interesting.add()).start(); 
new Thread(() -> interesting.compare()).start();
```

输出结果

然后，我们对add方法加锁，再次输出结果

**使用锁解决问题之前一定要理清楚，我们要保护的是什么逻辑，多线程执行的情况又是怎样的。**



### Q4-2：加锁的第二个误区-synchronized

**加锁前要清楚锁和被保护的对象是不是一个层面的**。

除了没有分析清线程、业务逻辑和锁三者之间的关系随意添加无效的方法锁外，还有一种比较常见的错误是，没有理清楚锁和要保护的对象是否是一个层面的。

我们知道**静态字段属于类，类级别的锁才能保护；而非静态字段属于类实例，实例级别的锁就可以保护。**

举例：在类 Data 中定义了一个静态的 int 字段 counter 和一个非静态的 wrong 方法，实现 counter 字段的累加操作。

示例代码如下；

```java
class Data { 
    @Getter 
    private static int counter = 0; 
    
    public static int reset() { 
        counter = 0; 
        return counter; 
    }
    
    public synchronized void wrong() { 
        counter++; 
    } 
}
```

写一段代码测试下：

```java
@GetMapping("wrong") 
public int wrong(@RequestParam(value = "count", defaultValue = "1000000") int count) {
    Data.reset();
    IntStream.rangeClosed(1, count).parallel().forEach(i -> new Data().wrong());
    return Data.getCounter();
}
```

因为默认运行 100 万次，所以执行后应该输出 100 万？











在非静态的 wrong 方法上加锁，只能确保多个线程无法执行同一个实例的 wrong 方法，却不能保证不会执行不同实例的 wrong 方法。而静态的 counter 在多个实例中共享，所以必然会出现线程安全问题。

理清思路后，修正方法就很清晰了：同样在类中定义一个 Object 类型的静态字段，在操作counter 之前对这个字段加锁。

```java
class Data { 
    @Getter 
    private static int counter = 0; 
    private static Object locker = new Object(); 
    
    public void right() { 
        synchronized (locker) { 
            counter++; 
        } 
    }
```



### Q4-3：加锁的第三个误区-锁粒度

在方法上加 synchronized 关键字实现加锁确实简单，也因此我曾看到一些业务代码中几乎所有方法都加了 synchronized，但这种滥用 synchronized 的做法：

一是，没必要。通常情况下 60% 的业务代码是三层架构，数据经过无状态的Controller、Service、Repository 流转到数据库，没必要使用 synchronized 来保护什么数据。

二是，可能会极大地降低性能。使用 Spring 框架时，默认情况下 Controller、Service、Repository 是单例的，加上 synchronized 会导致整个程序几乎就只能支持单线程，造成极大的性能问题。

**即使我们确实有一些共享资源需要保护，也要尽可能降低锁的粒度，仅对必要的代码块甚至是需要保护的资源本身加锁。**

比如，在业务代码中，有一个 ArrayList 因为会被多个线程操作而需要保护，又有一段比较耗时的操作（代码中的 slow 方法）不涉及线程安全问题，应该如何加锁呢？

错误的做法是，给整段业务逻辑加锁，把 slow 方法和操作 ArrayList 的代码同时纳入synchronized 代码块；更合适的做法是，把加锁的粒度降到最低，只在操作 ArrayList 的时候给这个 ArrayList 加锁。

```java

```

**如果精细化考虑了锁应用范围后，性能还无法满足需求的话，我们就要考虑另一个维度的粒度问题了，即：区分读写场景以及资源的访问冲突，考虑使用悲观方式的锁还是乐观方式的锁。**

一般业务代码中，很少需要进一步考虑这两种更细粒度的锁，所以我只和你分享几个大概的结论，你可以根据自己的需求来考虑是否有必要进一步优化：

对于读写比例差异明显的场景，考虑使用 **ReentrantReadWriteLock** 细化区分读写锁，来提高性能。

如果你的 JDK 版本高于 1.8、共享资源的冲突概率也没那么大的话，考虑使用**StampedLock** 的乐观读的特性，进一步提高性能。

JDK 里 **ReentrantLock** 和 **ReentrantReadWriteLock** 都提供了公平锁的版本，在没有明确需求的情况下不要轻易开启公平锁特性，在任务很轻的情况下开启公平锁可能会让性能下降上百倍。



### Q4-4：加锁的第三个误区-加多把锁导致死锁

锁的粒度够用就好，这就意味着我们的程序逻辑中有时会存在一些细粒度的锁。但一个业务逻辑如果涉及多把锁，容易产生死锁问题。

案例：下单操作需要锁定订单中多个商品的库存，拿到所有商品的锁之后进行下单扣减库存操作，全部操作完成之后释放所有的锁。

代码上线后发现，下单失败概率很高，失败后需要用户重新下单，极大影响了用户体验，还影响到了销量。

经排查发现是死锁引起的问题，背后原因是扣减库存的顺序不同，导致并发的情况下多个线程可能相互持有部分商品的锁，又等待其他线程释放另一部分商品的锁，于是出现了死锁问题。

示例代码如下：

首先，定义一个商品类型，包含商品名、库存剩余和商品的库存锁三个属性，每一种商品默认库存 1000 个；然后，初始化 10 个这样的商品对象来模拟商品清单

```java
private ConcurrentHashMap<String, Item> items = new ConcurrentHashMap<>();

public DeadLockController() {
    IntStream.range(0, 10).forEach(i -> items.put("item" + i, new Item("item" + i)));
}

@Data
@RequiredArgsConstructor
static class Item {
    final String name;
    int remaining = 1000;
    @ToString.Exclude
    ReentrantLock lock = new ReentrantLock();
}
```

随后，写一个方法模拟在购物车进行商品选购，每次从商品清单（items 字段）中随机选购三个商品（为了逻辑简单，我们不考虑每次选购多个同类商品的逻辑，购物车中不体现商品数量）：

```java
private List<Item> createCart() {
    return IntStream.rangeClosed(1, 3)
        .mapToObj(i -> "item" + ThreadLocalRandom.current().nextInt(items.size()))
        .map(name -> items.get(name)).collect(Collectors.toList());
}
```

下单代码如下：先声明一个 List 来保存所有获得的锁，然后遍历购物车中的商品依次尝试获得商品的锁，最长等待 10 秒，获得全部锁之后再扣减库存；如果有无法获得锁的情况则解锁之前获得的所有锁，返回 false 下单失败。

```java
 private boolean createOrder(List<Item> order) {
     List<ReentrantLock> locks = new ArrayList<>();

     for (Item item : order) {
         try {
             if (item.lock.tryLock(10, TimeUnit.SECONDS)) {
                 locks.add(item.lock);
             } else {
                 locks.forEach(ReentrantLock::unlock);
                 return false;
             }
         } catch (InterruptedException e) {
         }
     }
     try {
         order.forEach(item -> item.remaining--);
     } finally {
         locks.forEach(ReentrantLock::unlock);
     }
     return true;
 }
```

我们写一段代码测试这个下单操作。模拟在多线程情况下进行 100 次创建购物车和下单操作，最后通过日志输出成功的下单次数、总剩余的商品个数、100 次下单耗时，以及下单完成后的商品库存明细：

```java
@GetMapping("wrong4")
public long wrong() {
    long begin = System.currentTimeMillis();
    long success = IntStream.rangeClosed(1, 100).parallel()
        .mapToObj(i -> {
            List<Item> cart = createCart();
            return createOrder(cart);
        })
        .filter(result -> result).count();
    log.info("success:{} totalRemaining:{} took:{}ms items:{}", success,
             items.entrySet().stream().map(item -> item.getValue().remaining).reduce(0, Integer::sum),
             System.currentTimeMillis() - begin, items);
    return success;
}
```

由于我们的任务需要 1 小时才能执行完成，大量的任务进来后会创建大量的线程。我们知道线程是需要分配一定的内存空间作为线程栈的，比如 1MB，因此无限制创建线程必然会导致 OOM。











那为什么会有死锁问题呢？

我们仔细回忆一下购物车添加商品的逻辑，随机添加了三种商品，假设一个购物车中的商品是 item1 和 item2，另一个购物车中的商品是 item2 和 item1，一个线程先获取到了item1 的锁，同时另一个线程获取到了 item2 的锁，然后两个线程接下来要分别获取item2 和 item1 的锁，这个时候锁已经被对方获取了，只能相互等待一直到 10 秒超时。







其实，避免死锁的方案很简单，**为购物车中的商品排一下序，让所有的线程一定是先获取item1 的锁然后获取 item2 的锁，就不会有问题了**。所以，我只需要修改一行代码，对createCart 获得的购物车按照商品名进行排序即可：

```java
@GetMapping("right4")
public long right() {
    long begin = System.currentTimeMillis();
    long success = IntStream.rangeClosed(1, 100).parallel()
        .mapToObj(i -> {
            List<Item> cart = createCart().stream()
                .sorted(Comparator.comparing(Item::getName))
                .collect(Collectors.toList());
            return createOrder(cart);
        })
        .filter(result -> result)
        .count();
    log.info("success:{} totalRemaining:{} took:{}ms items:{}",
             success,
             items.entrySet().stream().map(item -> item.getValue().remaining).reduce(0, Integer::sum),
             System.currentTimeMillis() - begin, items);
    return success;
}
```











小结：这个案例中，虽然产生了死锁问题，但因为尝试获取锁的操作并不是无限阻塞的，所以没有造成永久死锁，之后的改进就是避免循环等待，通过对购物车的商品进行排序来实现有顺序的加锁，避免循环等待。



### Q5-1：线程池的正确使用-线程池的工作行为

Java 中的 Executors 类定义了一些快捷的工具方法，来帮助我们快速创建线程池。《阿里巴巴 Java 开发手册》中提到，禁止使用这些方法来创建线程池，而应该手动 new **ThreadPoolExecutor** 来创建线程池。这一条规则的背后，是大量血淋淋的生产事故，最典型的就是 **newFixedThreadPool** 和**newCachedThreadPool**，可能因为资源耗尽导致OOM 问题。

举例：我们写一段测试代码，来初始化一个单线程的 FixedThreadPool，循环 1 亿次向线程池提交任务，每个任务都会创建一个比较大的字符串然后休眠一小时。

```java
@GetMapping("oom1")
public void oom1() throws InterruptedException {
    ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    printStats(threadPool);
    for (int i = 0; i < 100000000; i++) {
        threadPool.execute(() -> {
            String payload = IntStream.rangeClosed(1, 1000000)
                .mapToObj(__ -> "a")
                .collect(Collectors.joining("")) + UUID.randomUUID().toString();
            try {
                TimeUnit.HOURS.sleep(1);
            }
            catch (InterruptedException e) {
            }
            log.info(payload);
        });
    }

    threadPool.shutdown();
    threadPool.awaitTermination(1, TimeUnit.HOURS);
}
```

翻看 newFixedThreadPool 方法的源码不难发现，线程池的工作队列直接 new 了一个LinkedBlockingQueue，**而默认构造方法的 LinkedBlockingQueue 是一个Integer.MAX_VALUE 长度的队列，可以认为是无界的**：

```java
public static ExecutorService newFixedThreadPool(int nThreads) {
        return new ThreadPoolExecutor(nThreads, nThreads,
                                      0L, TimeUnit.MILLISECONDS,
                                      new LinkedBlockingQueue<Runnable>());
}

 /**
 * Creates a {@code LinkedBlockingQueue} with a capacity of
 * {@link Integer#MAX_VALUE}.
 */
public LinkedBlockingQueue() {
    this(Integer.MAX_VALUE);
}
```

虽然使用 newFixedThreadPool 可以把工作线程控制在固定的数量上，但任务队列是无界的。如果任务较多并且执行较慢的话，队列可能会快速积压，撑爆内存导致 OOM。













虽然使用 newFixedThreadPool 可以把工作线程控制在固定的数量上，但任务队列是无界的。如果任务较多并且执行较慢的话，队列可能会快速积压，撑爆内存导致 OOM。

改用newCachedThreadPool的例子如下：

```java
@GetMapping("oom2")
public void oom2() throws InterruptedException {

    ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    printStats(threadPool);
    for (int i = 0; i < 100000000; i++) {
        threadPool.execute(() -> {
            String payload = UUID.randomUUID().toString();
            try {
                TimeUnit.HOURS.sleep(1);
            } catch (InterruptedException e) {
            }
            log.info(payload);
        });
    }
    threadPool.shutdown();
    threadPool.awaitTermination(1, TimeUnit.HOURS);
}
```

从日志中可以看到，这次 OOM 的原因是无法创建线程，翻看 newCachedThreadPool 的源码可以看到，**这种线程池的最大线程数是 Integer.MAX_VALUE，可以认为是没有上限的，而其工作队列 SynchronousQueue 是一个没有存储空间的阻塞队列**

```java
public static ExecutorService newCachedThreadPool() {
    return new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                                  60L, TimeUnit.SECONDS,
                                  new SynchronousQueue<Runnable>());
}

 /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
public SynchronousQueue() {
    this(false);
}
```

这意味着，只要有请求到来，就必须找到一条工作线程来处理，如果当前没有空闲的线程就再创建一条新的。



小结：我们需要根据自己的场景、并发情况来评估线程池的几个核心参数，包括核心线程数、最大线程数、线程回收策略、工作队列的类型，以及拒绝策略，确保线程池的工作行为符合需求，一般都需要设置有界的工作队列和可控的线程数。

任何时候，都应该为自定义线程池指定有意义的名称，以方便排查问题。当出现线程数量暴增、线程死锁、线程占用大量 CPU、线程执行出现异常等问题时，我们往往会抓取线程栈。此时，有意义的线程名称，就可以方便我们定位问题。

正确的线程池使用方法举例如下：

在这个例子里，首先，自定义一个线程池。这个线程池具有 2 个核心线程、5 个最大线程、使用容量为 10的 ArrayBlockingQueue 阻塞队列作为工作队列，使用默认的 AbortPolicy 拒绝策略，也就是任务添加到线程池失败会抛出 RejectedExecutionException。此外，我们借助了Jodd 类库的 ThreadFactoryBuilder 方法来构造一个线程工厂，实现线程池线程的自定义命名。然后，我们写一段测试代码来观察线程池管理线程的策略。测试代码的逻辑为，每次间隔 1秒向线程池提交任务，循环 20 次，每个任务需要 10 秒才能执行完成。

```java
@GetMapping("right")
    public int right() throws InterruptedException {
        AtomicInteger atomicInteger = new AtomicInteger();
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                2, 5,
                5, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10),
                new ThreadFactoryBuilder().setNameFormat("demo-threadpool-%d").get(),
                new ThreadPoolExecutor.AbortPolicy());
        //threadPool.allowCoreThreadTimeOut(true);
        printStats(threadPool);
        IntStream.rangeClosed(1, 20).forEach(i -> {
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            int id = atomicInteger.incrementAndGet();
            try {
                threadPool.submit(() -> {
                    log.info("{} started", id);
                    try {
                        TimeUnit.SECONDS.sleep(10);
                    } catch (InterruptedException e) {
                    }
                    log.info("{} finished", id);
                });
            } catch (Exception ex) {
                log.error("error submitting task {}", id, ex);
                atomicInteger.decrementAndGet();
            }
        });

        TimeUnit.SECONDS.sleep(60);
        return atomicInteger.intValue();
    }
```

小结：**线程池默认的工作行为**

不会初始化 corePoolSize 个线程，有任务来了才创建工作线程；

当核心线程满了之后不会立即扩容线程池，而是把任务堆积到工作队列中；

当工作队列满了后扩容线程池，一直到线程个数达到 maximumPoolSize 为止；

如果队列已满且达到了最大线程后还有任务进来，按照拒绝策略处理；

当线程数大于核心线程数时，线程等待 keepAliveTime 后还是没有任务需要处理的话，收缩线程到核心线程数。

了解这个策略，有助于我们根据实际的容量规划需求，为线程池设置合适的初始化参数。当然，我们也可以通过一些手段来改变这些默认工作行为，比如：

声明线程池后立即调用 **prestartAllCoreThreads** 方法，来启动所有核心线程；

传入 true 给 **allowCoreThreadTimeOut** 方法，来让线程池在空闲的时候同样回收核心线程。

Java 线程池是先用工作队列来存放来不及处理的任务，满了之后再扩容线程池。当我们的工作队列设置得很大时，最大线程数这个参数显得没有意义，因为队列很难满，或者到满的时候再去扩容线程池已经于事无补了。

**有没有办法让线程池更激进一点，优先开启更多的线程，而把队列当成一个后备方案呢？**比如我们这个例子，任务执行得很慢，需要 10 秒，如果线程池可以优先扩容到 5个最大线程，那么这些任务最终都可以完成，而不会因为线程池扩容过晚导致慢任务来不及处理。



### Q5-2：线程池的正确使用-线程池的复用

示例代码如下：

```java
@GetMapping("wrong")
public String wrong() throws InterruptedException {
    ThreadPoolExecutor threadPool = ThreadPoolHelper.getThreadPool();
    IntStream.rangeClosed(1, 10).forEach(i -> {
        threadPool.execute(() -> {
            String payload = IntStream.rangeClosed(1, 1000000)
                .mapToObj(__ -> "a")
                .collect(Collectors.joining("")) + UUID.randomUUID().toString();
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
            }
            log.debug(payload);
        });
    });
    return "OK";
}

static class ThreadPoolHelper {
    private static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
        10, 50,
        2, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000),
        new ThreadFactoryBuilder().setNameFormat("demo-threadpool-%d").get());

    public static ThreadPoolExecutor getThreadPool() {
        return (ThreadPoolExecutor) Executors.newCachedThreadPool();
    }

    static ThreadPoolExecutor getRightThreadPool() {
        return threadPoolExecutor;
    }
}
```

思考：

按上述代码，newCachedThreadPool 会在需要时创建必要多的线程，业务代码的一次业务操作会向线程池提交多个慢任务，这样执行一次业务操作就会开启

多个线程。如果业务操作并发量较大的话，的确有可能一下子开启几千个线程。

**那为什么我们能在监控中看到线程数量会下降，而不会撑爆内存呢？**

**线程池的意义在于复用，那这是不是意味着程序应该始终使用一个线程池呢？**



### Q5-3：线程池的正确使用-混合线程池策略

**策略原则：**

**根据任务的“轻重缓急”来指定线程池的核心参数，包括线程数、回收策略和任务队列**

**执行比较慢、数量不大的 IO 任务：增加线程数，减少队列容量。**

**吞吐量较大的计算型任务，线程数量不宜过多，可以是 CPU 核数或核数 *2，但可能需要较长的队列来做缓冲。**

示例代码：

```java
private static ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
    2, 2,
    1, TimeUnit.HOURS,
    new ArrayBlockingQueue<>(100),
    new ThreadFactoryBuilder().setNameFormat("batchfileprocess-threadpool-%d").get(),
    new ThreadPoolExecutor.CallerRunsPolicy());

private Callable<Integer> calcTask() {
        return () -> {
            TimeUnit.MILLISECONDS.sleep(10);
            return 1;
        };
    }

@GetMapping("wrong2")
public int wrong() throws ExecutionException, InterruptedException {
    return threadPool.submit(calcTask()).get();
}
```

这里，我们模拟一下文件批处理的代码，在程序启动后通过一个线程开启死循环逻辑，不断向线程池提交任务，任务的逻辑是向一个文件中写入大量的数据：

```java
private void printStats(ThreadPoolExecutor threadPool) {
    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
        log.info("=========================");
        log.info("Pool Size: {}", threadPool.getPoolSize());
        log.info("Active Threads: {}", threadPool.getActiveCount());
        log.info("Number of Tasks Completed: {}", threadPool.getCompletedTaskCount());
        log.info("Number of Tasks in Queue: {}", threadPool.getQueue().size());

        log.info("=========================");
    }, 0, 1, TimeUnit.SECONDS);
}

@PostConstruct
public void init() {
    printStats(threadPool);

    new Thread(() -> {
        String payload = IntStream.rangeClosed(1, 1_000_000)
            .mapToObj(__ -> "a")
            .collect(Collectors.joining(""));
        while (true) {
            threadPool.execute(() -> {
                try {
                    Files.write(Paths.get("demo.txt"), Collections.singletonList(LocalTime.now().toString() + ":" + payload), 										UTF_8, CREATE, TRUNCATE_EXISTING);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                log.info("batch file processing done");
            });
        }
    }).start();
}
```

**通过监控看到，线程池的 2 个线程始终处于活跃状态，队列也基本处于打满状态。**因为开启了CallerRunsPolicy 拒绝处理策略，所以当线程满载队列也满的情况下，任务会在提交任务的线程，或者说调用 execute 方法的线程执行，也就是说不能认为提交到线程池的任务就一定是异步处理的。如果使用了 CallerRunsPolicy 策略，那么有可能异步任务变为同步执行。如下图红框所示。这也是这个拒绝策略比较特别的原因。

![image-20200607182051727](C:\Users\Max Yu\AppData\Roaming\Typora\typora-user-images\image-20200607182051727.png)

如果使用wrk等压测工具对上述接口进行压测，可以得到比较低的TPS数据。

因为原来执行 IO 任务的线程池使用的是CallerRunsPolicy 策略，所以直接使用这个线程池进行异步计算的话，**当线程池饱和的时候，计算任务会在执行 Web 请求的 Tomcat 线程执行，这时就会进一步影响到其他同步处理的线程，甚至造成整个应用程序崩溃**。

解决方案很简单，使用独立的线程池来做这样的“计算任务”即可。计算任务打了双引号，是因为我们的模拟代码执行的是休眠操作，并不属于 CPU 绑定的操作，更类似 IO 绑定的操作，如果线程池线程数设置太小会限制吞吐能力：

优化后的代码如下所示：

```java
 private static ThreadPoolExecutor asyncCalcThreadPool = new ThreadPoolExecutor(
            200, 200,
            1, TimeUnit.HOURS,
            new ArrayBlockingQueue<>(1000),
            new ThreadFactoryBuilder().setNameFormat("asynccalc-threadpool-%d").get());

@GetMapping("right2")
public int right() throws ExecutionException, InterruptedException {
    return asyncCalcThreadPool.submit(calcTask()).get();
}
```

此时，如果使用wrk等压测工具对上述接口进行压测，可以得到比较高的TPS数据。

小结：

**可以看到，盲目复用线程池混用线程的问题在于，别人定义的线程池属性不一定适合你的任务，而且混用会相互干扰。这就好比，我们往往会用虚拟化技术来实现资源的隔离，而不是让所有应用程序都直接使用物理机。**

注意：

**Java 8 的 parallel stream 功能，可以让我们很方便地并行处理集合中的元素，其背后是共享同一个 ForkJoinPool，默认并行度是CPU 核数 -1**。对于 CPU 绑定的任务来说，使用这样的配置比较合适，但如果集合操作涉及同步 IO 操作的话（比如数据库操作、外部服务调用等），建议自定义一个ForkJoinPool（或普通线程池）。



### Q5-4：线程池的正确使用-总结

第一，Executors 类提供的一些快捷声明线程池的方法虽然简单，但隐藏了线程池的参数细节。因此，使用线程池时，我们一定要根据场景和需求配置合理的线程数、任务队列、拒绝策略、线程回收策略，并对线程进行明确的命名方便排查问题。

第二，既然使用了线程池就需要确保线程池是在复用的，每次 new 一个线程池出来可能比不用线程池还糟糕。如果你没有直接声明线程池而是使用其他同学提供的类库来获得一个线程池，请务必查看源码，以确认线程池的实例化方式和配置是符合预期的。

第三，复用线程池不代表应用程序始终使用同一个线程池，我们应该根据任务的性质来选用不同的线程池。特别注意 IO 绑定的任务和 CPU 绑定的任务对于线程池属性的偏好，如果希望减少任务间的相互干扰，考虑按需使用隔离的线程池。



### Q6-1：判等问题-equals和==的区别

**1. equals** **和** **==** **的区别**

在业务代码中，我们通常使用 equals 或 == 进行判等操作。equals 是方法而 == 是操作符，它们的使用是有区别的：

对基本类型，比如 int、long，进行判等，只能使用 ==，比较的是直接值。因为基本类型的值就是其数值。

对引用类型，比如 Integer、Long 和 String，进行判等，需要使用 equals 进行内容判等。因为引用类型的直接值是指针，使用 == 的话，比较的是指针，也就是两个对象在内存中的地址，即比较它们是不是同一个对象，而不是比较对象的内容。

小结：**比较值的内容，除了基本类型只能使用 ==外，其他类型都需要使用 equals**。

**2. 整型对象比较示例**

使用 == 对两个值为 127 的直接赋值的 Integer 对象判等；

使用 == 对两个值为 128 的直接赋值的 Integer 对象判等；

使用 == 对一个值为 127 的直接赋值的 Integer 和另一个通过 new Integer 声明的值为127 的对象判等；

使用 == 对两个通过 new Integer 声明的值为 127 的对象判等；

使用 == 对一个值为 128 的直接赋值的 Integer 对象和另一个值为 128 的 int 基本类型判等

代码如下：

```java
 @GetMapping("intcompare")
public void intcompare() {

    Integer a = 127; //Integer.valueOf(127)
    Integer b = 127; //Integer.valueOf(127)
    log.info("\nInteger a = 127;\n" +
             "Integer b = 127;\n" +
             "a == b ? {}", a == b);    // true

    Integer c = 128; //Integer.valueOf(128)
    Integer d = 128; //Integer.valueOf(128)
    log.info("\nInteger c = 128;\n" +
             "Integer d = 128;\n" +
             "c == d ? {}", c == d);   //false
    //设置-XX:AutoBoxCacheMax=1000再试试

    Integer e = 127; //Integer.valueOf(127)
    Integer f = new Integer(127); //new instance
    log.info("\nInteger e = 127;\n" +
             "Integer f = new Integer(127);\n" +
             "e == f ? {}", e == f);   //false

    Integer g = new Integer(127); //new instance
    Integer h = new Integer(127); //new instance
    log.info("\nInteger g = new Integer(127);\n" +
             "Integer h = new Integer(127);\n" +
             "g == h ? {}", g == h);  //false

    Integer i = 128; //unbox
    int j = 128;
    log.info("\nInteger i = 128;\n" +
             "int j = 128;\n" +
             "i == j ? {}", i == j); //true

}
```

**3. 枚举对象比较示例**

```java
@PostMapping("enumcompare")
public void enumcompare(@RequestBody OrderQuery orderQuery) {
    StatusEnum statusEnum = StatusEnum.DELIVERED;
    log.info("orderQuery:{} statusEnum:{} result:{}", orderQuery, statusEnum, statusEnum.status == orderQuery.getStatus());
}

enum StatusEnum {
    CREATED(1000, "已创建"),
    PAID(1001, "已支付"),
    DELIVERED(1002, "已送到"),
    FINISHED(1003, "已完成");

    private final Integer status;
    private final String desc;

    StatusEnum(Integer status, String desc) {
        this.status = status;
        this.desc = desc;
    }
}
```

**4. 字符串对象比较示例**

```java
@GetMapping("stringcompare")
public void stringcomare() {
    String a = "1";
    String b = "1";
    log.info("\nString a = \"1\";\n" +
             "String b = \"1\";\n" +
             "a == b ? {}", a == b); //true

    String c = new String("2");
    String d = new String("2");
    log.info("\nString c = new String(\"2\");\n" +
             "String d = new String(\"2\");" +
             "c == d ? {}", c == d); //false

    String e = new String("3").intern();
    String f = new String("3").intern();
    log.info("\nString e = new String(\"3\").intern();\n" +
             "String f = new String(\"3\").intern();\n" +
             "e == f ? {}", e == f); //true

    String g = new String("4");
    String h = new String("4");
    log.info("\nString g = new String(\"4\");\n" +
             "String h = new String(\"4\");\n" +
             "g == h ? {}", g.equals(h)); //true
}
```









 Java 的字符串常量池机制，其设计初衷是节省内存。当代码中出现双引号形式创建字符串对象时，JVM 会先对这个字符串进行检查，如果字符串常量池中存在相同内容的字符串对象的引用，则将这个引用返回；否则，创建新的字符串对象，然后将这个引用放入字符串常量池，并返回该引用。这种机制，就是字符串驻留或池化。



### Q6-2：判等问题-intern方法的坑点

**1. intern方法**

**虽然使用 new 声明的字符串调用 intern 方法，也可以让字符串进行驻留，但在业务代码中滥用 intern，可能会产生性能问题**。

示例代码如下：

通过循环把 1 到 1000 万之间的数字以字符串形式 intern 后，存入一个List：

```java
@GetMapping("internperformance")
public int internperformance(@RequestParam(value = "size", defaultValue = "10000000") int size) {
    //-XX:+PrintStringTableStatistics
    //-XX:StringTableSize=10000000
    long begin = System.currentTimeMillis();
    list = IntStream.rangeClosed(1, size)
        .mapToObj(i -> String.valueOf(i).intern())
        .collect(Collectors.toList());
    log.info("size:{} took:{}", size, System.currentTimeMillis() - begin);
    return list.size();
}
```

**2. equals()方法的实现**

Object.equals()方法的实现其实是比较对象引用。

之所以 Integer 或 String 能通过 equals 实现内容判等，是因为它们都重写了这个方法。

对于自定义类型，如果不重写 equals 的话，默认就是使用 Object 基类的按引用的比较方式。

代码示例：

```java
 class Point {
     private final String desc;
     private int x;
     private int y;

     public Point(int x, int y, String desc) {
         this.x = x;
         this.y = y;
         this.desc = desc;
     }
 }

@GetMapping("wrong")
public void wrong() {
    Point p1 = new Point(1, 2, "a");
    Point p2 = new Point(1, 2, "b");
    Point p3 = new Point(1, 2, "a");
    log.info("p1.equals(p2) ? {}", p1.equals(p2));
    log.info("p1.equals(p3) ? {}", p1.equals(p3));

}
```

代码示例：

```java

class PointWrong {
    private final String desc;
    private int x;
    private int y;

    public PointWrong(int x, int y, String desc) {
        this.x = x;
        this.y = y;
        this.desc = desc;
    }

    @Override
    public boolean equals(Object o) {
        PointWrong that = (PointWrong) o;
        return x == that.x && y == that.y;
    }
}

@GetMapping("wrong2")
public void wrong2() {
    PointWrong p1 = new PointWrong(1, 2, "a");
    try {
        log.info("p1.equals(null) ? {}", p1.equals(null));
    } catch (Exception ex) {
        log.error(ex.toString());
    }

    Object o = new Object();
    try {
        log.info("p1.equals(expression) ? {}", p1.equals(o));
    } catch (Exception ex) {
        log.error(ex.toString());
    }

    PointWrong p2 = new PointWrong(1, 2, "b");
    log.info("p1.equals(p2) ? {}", p1.equals(p2));

}
```

小结：

实现一个更好的 equals 应该注意的点：

考虑到性能，可以先进行指针判等，如果对象是同一个那么直接返回 true；

需要对另一方进行判空，空对象和自身进行比较，结果一定是 false；

需要判断两个对象的类型，如果类型都不同，那么直接返回 false；确保类型相同的情况下再进行类型强制转换，然后逐一判断所有字段。

改进后的代码如下：

```java
class PointRight {
    private final int x;
    private final int y;
    private final String desc;

    public PointRight(int x, int y, String desc) {
        this.x = x;
        this.y = y;
        this.desc = desc;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PointRight that = (PointRight) o;
        return x == that.x && y == that.y;
    }
   
}
```



### Q6-3: 判等问题-集合元素的存在判断

举例：定义两个 x 和 y 属性值完全一致的 Point 对象 p1 和 p2，把p1 加入 HashSet，然后判断这个 Set 中是否存在 p2

代码示例

```java
PointWrong p1 = new PointWrong(1, 2, "a");
HashSet<PointWrong> points = new HashSet<>();
points.add(p1);
PointWrong p2 = new PointWrong(1, 2, "b");
log.info("points.contains(p2) ? {}", points.contains(p2));
```













按照改进后的 equals 方法，这 2 个对象可以认为是同一个，Set 中已经存在了 p1 就应该包含 p2，但结果却是 false。

出现这个 Bug 的原因是，散列表需要使用 hashCode 来定位元素放到哪个桶。如果自定义对象没有实现自定义的 hashCode 方法，就会使用 Object 超类的默认实现，**得到的两个hashCode 是不同的，导致无法满足需求**。

要自定义 hashCode，我们可以直接使用 Objects.hash 方法来实现，改进后的 Point 类如下：

```java
class PointRight {
    private final int x;
    private final int y;
    private final String desc;

    public PointRight(int x, int y, String desc) {
        this.x = x;
        this.y = y;
        this.desc = desc;
    }

    ......

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }
}

@GetMapping("right")
public void right() {
    PointRight p1 = new PointRight(1, 2, "a");
    PointRight p2 = new PointRight(1, 2, "b");
   
    HashSet<PointRight> points = new HashSet<>();
    points.add(p1);
    log.info("points.contains(p2) ? {}", points.contains(p2));
}
```



### Q6-4:  compareTo **和** **equals** 的逻辑一致性

除了自定义类型需要确保 equals 和 hashCode 要逻辑一致外，还有一个更容易被忽略的问题，即 compareTo 同样需要和 equals 确保逻辑一致性。

示例：

首先，定义一个 Student 类，有 id 和 name 两个属性，并实现了一个 Comparable 接口来返回两个 id 的值：



然后，写一段测试代码分别通过 indexOf 方法和 Collections.binarySearch 方法进行搜索。列表中我们存放了两个学生，第一个学生 id 是 1 叫 zhang，第二个学生 id 是 2 叫wang，搜索这个列表是否存在一个 id 是 2 叫 li 的学生。



