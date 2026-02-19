# Java SE API Reference (Test Automation & General Development)

## Collections

### List / ArrayList / LinkedList
```java
List<String> list = List.of("a", "b", "c");       // immutable
List<String> mut  = new ArrayList<>(List.of("a")); // mutable copy

// ArrayList — O(1) random access, O(n) mid-list insert/remove
var a = new ArrayList<String>();
a.add("x");           a.add(0, "y");      // append / insert at index
a.get(0);             a.set(0, "z");      // read / replace at index
a.remove(0);          a.remove("x");      // remove by index / value
a.size();             a.isEmpty();        a.contains("x");
a.indexOf("x");       a.stream();         a.subList(0, 2);
a.toArray(String[]::new);

// LinkedList — also implements Deque
var ll = new LinkedList<String>();
ll.addFirst("a"); ll.addLast("b"); ll.peekFirst(); ll.pollLast();
```

### Map / HashMap / LinkedHashMap
```java
Map<String,Integer> m = Map.of("a",1,"b",2);            // immutable
Map<String,Integer> m2 = Map.ofEntries(Map.entry("a",1));

var map = new HashMap<String,Integer>();
map.put("k", 1);           map.putIfAbsent("k", 2);
map.get("k");               map.getOrDefault("x", 0);
map.containsKey("k");       map.containsValue(1);
map.remove("k");            map.size();
map.keySet();                map.values();       map.entrySet();
map.forEach((k, v) -> System.out.println(k + "=" + v));
map.merge("k", 1, Integer::sum);       // upsert
map.computeIfAbsent("k", k -> 42);     // lazy init

// LinkedHashMap — same API, preserves insertion order
```

### Set / HashSet
```java
Set<String> s = new HashSet<>(List.of("a","b","a")); // {"a","b"}
s.add("c"); s.remove("a"); s.contains("b"); s.size();
s.retainAll(other); // intersection    s.addAll(other); // union
Set<String> immut = Set.of("a","b");
```

### Collections Utilities
```java
Collections.sort(list);                           // natural order
Collections.sort(list, Comparator.reverseOrder());
Collections.unmodifiableList(list);               // read-only view
Collections.singletonList("x");                  // immutable single
Collections.emptyList();   Collections.reverse(list);
```

---

## Streams

### Creation
```java
Stream.of("a", "b", "c");                    // varargs
list.stream();                                // from Collection
Arrays.stream(array);                         // from array
Stream.generate(() -> "x");                   // infinite
Stream.iterate(0, n -> n < 10, n -> n + 1);  // bounded
Stream.empty();     "a\nb".lines();           // empty / from string
```

### Intermediate Ops (lazy)
```java
stream.filter(s -> s.startsWith("a"))   // keep matching
      .map(String::toUpperCase)         // transform 1:1
      .flatMap(s -> s.chars().boxed())  // transform 1:N, flatten
      .sorted()                         // natural order
      .sorted(Comparator.comparing(String::length))
      .distinct()                       // deduplicate
      .peek(System.out::println)        // debug side-effect
      .limit(10).skip(5)               // take/drop
      .takeWhile(s -> s.length() < 5)
      .dropWhile(s -> s.length() < 5);
```

### Terminal Ops (eager)
```java
stream.collect(Collectors.toList());  // mutable List
stream.toList();                      // unmodifiable List (16+)
stream.forEach(System.out::println);
stream.count();                       // long
stream.findFirst();                   // Optional<T>
stream.anyMatch(s -> s.isEmpty());    // boolean
stream.allMatch(s -> s.length() > 0);
stream.noneMatch(s -> s.isEmpty());
stream.reduce("", String::concat);   // fold with identity
stream.reduce(Integer::sum);          // Optional fold
stream.toArray(String[]::new);
stream.min(Comparator.naturalOrder()); stream.max(Comparator.naturalOrder());
```

### Collectors
```java
Collectors.toList()                             // mutable ArrayList
Collectors.toSet()                              // mutable HashSet
Collectors.toMap(k -> k, v -> v.length())       // key/value mappers
Collectors.toMap(k -> k, v -> 1, Integer::sum)  // with merge fn
Collectors.groupingBy(String::length)           // Map<Int,List<Str>>
Collectors.groupingBy(String::length, Collectors.counting())
Collectors.partitioningBy(s -> s.length() > 3)  // Map<Bool,List>
Collectors.joining(", ", "[", "]")              // "[a, b, c]"
```

---

## String

```java
String.format("Hello %s, age %d", name, age);  // printf-style
"hello".substring(1);          // "ello"
"hello".substring(1, 3);      // "el" (inclusive, exclusive)
"a,b,c".split(",");           // String[]{"a","b","c"}
"a,b,c".split(",", 2);        // String[]{"a","b,c"}
"hello".replace("l", "r");    // "herro" (literal)
"hello".replaceAll("l+", "r"); // regex
"abc123".matches("\\w+");     // true (full-string regex)
"  hi  ".strip();              // "hi" (Unicode-aware trim)
"hello".isBlank();  "hello".isEmpty();
String.valueOf(42);            // "42"
"hello".contains("ell");  "hello".startsWith("he");  "hello".endsWith("lo");
"hello".charAt(0);   "hello".toUpperCase();   "hello".toLowerCase();
String.join(", ", list);       // "a, b, c"
"ha".repeat(3);                // "hahaha" (11+)

// StringBuilder
var sb = new StringBuilder();
sb.append("hello").append(" world");
sb.insert(5, ","); sb.delete(5, 6); sb.reverse(); sb.toString();
```

---

## Optional

```java
Optional.of("value");           // throws if null
Optional.ofNullable(value);     // null-safe
Optional.empty();

opt.isPresent();  opt.isEmpty();               // 11+
opt.ifPresent(v -> use(v));
opt.ifPresentOrElse(v -> use(v), () -> fallback());

opt.orElse("default");
opt.orElseGet(() -> compute());                // lazy default
opt.orElseThrow(() -> new IllegalStateException("missing"));

opt.map(String::toUpperCase);                  // Optional<String>
opt.flatMap(v -> lookupOpt(v));                // avoids nested Optional
opt.filter(v -> v.length() > 3);              // empty if predicate fails
opt.stream();                                  // Stream of 0 or 1
opt.or(() -> Optional.of("other"));            // chain (9+)
```

---

## Files / Path (java.nio)

```java
Path p = Path.of("src", "main", "App.java");
p.getFileName();  p.getParent();  p.resolve("child");  p.toAbsolutePath();

// Read / Write
String content = Files.readString(Path.of("f.txt"));
List<String> lines = Files.readAllLines(Path.of("f.txt"));
Files.writeString(Path.of("out.txt"), "content");
Files.writeString(Path.of("out.txt"), "more", StandardOpenOption.APPEND);
Files.write(Path.of("out.txt"), lines);

// Directory listing
Stream<Path> children = Files.list(Path.of("dir"));       // direct
Stream<Path> tree     = Files.walk(Path.of("dir"));        // recursive
Stream<Path> javaFiles = Files.walk(Path.of("dir"))
    .filter(f -> f.toString().endsWith(".java"));

// Operations
Files.exists(p);  Files.isRegularFile(p);  Files.isDirectory(p);
Files.createDirectories(Path.of("a/b/c"));
Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
Files.move(src, dst);  Files.delete(p);  Files.size(p);
```

---

## CompletableFuture

```java
// Creation
var cf = CompletableFuture.supplyAsync(() -> fetchData());
CompletableFuture.runAsync(() -> doWork());
CompletableFuture.completedFuture("done");

// Chaining
cf.thenApply(s -> s.toUpperCase())        // sync transform
  .thenAccept(System.out::println)        // consume -> CF<Void>
  .thenRun(() -> cleanup());              // run after -> CF<Void>
cf.thenCompose(s -> fetchMoreAsync(s));   // async chain (flatMap)
cf.thenCombine(other, (a, b) -> a + b);  // combine two futures

// Extract
cf.join();                                // block, unchecked exception
cf.get(5, TimeUnit.SECONDS);             // block with timeout

// Combine multiple
CompletableFuture.allOf(cf1, cf2, cf3).join();
CompletableFuture.anyOf(cf1, cf2).thenAccept(v -> use(v));

// Errors
cf.exceptionally(ex -> "fallback");
cf.handle((res, ex) -> ex != null ? "err" : res);
cf.whenComplete((res, ex) -> log(res, ex));
```

---

## Common Patterns

### try-with-resources
```java
try (var reader = new BufferedReader(new FileReader("f.txt"));
     var writer = new BufferedWriter(new FileWriter("out.txt"))) {
    String line;
    while ((line = reader.readLine()) != null) writer.write(line);
} // auto-closed even on exception
```

### var (Java 10+)
```java
var list = new ArrayList<String>();   // ArrayList<String>
var map  = Map.of("a", 1);           // Map<String, Integer>
// Cannot use for: fields, method params, return types, null init
```

### Records (Java 16+)
```java
public record Point(int x, int y) {}
// Auto: constructor, x(), y(), equals, hashCode, toString
var p = new Point(1, 2); p.x(); // 1

public record Range(int lo, int hi) {
    public Range { if (lo > hi) throw new IllegalArgumentException(); }
}
```

### Sealed Classes (Java 17+)
```java
public sealed interface Shape permits Circle, Rectangle {}
public record Circle(double r) implements Shape {}
public record Rectangle(double w, double h) implements Shape {}
```

### Pattern Matching (instanceof 16+, switch 21+)
```java
if (obj instanceof String s && s.length() > 3) { use(s); }

String desc = switch (shape) {
    case Circle c when c.r() > 10 -> "large circle";
    case Circle c    -> "circle r=" + c.r();
    case Rectangle r -> "rect " + r.w() + "x" + r.h();
}; // exhaustive for sealed types — no default needed

String proc = switch (obj) {
    case null      -> "null";
    case Integer i -> "int: " + i;
    case String s  -> "str: " + s;
    default        -> "other";
};
```

### Text Blocks (Java 15+)
```java
String json = """
        { "name": "%s", "age": %d }
        """.formatted(name, age);
```

### Functional Interfaces (java.util.function)
```java
Function<String, Integer>    fn = String::length;      // T -> R
Predicate<String>            p  = s -> s.isEmpty();     // T -> bool
Consumer<String>             c  = System.out::println;  // T -> void
Supplier<String>             s  = () -> "hello";        // () -> T
UnaryOperator<String>        u  = String::toUpperCase;  // T -> T
BinaryOperator<Integer>      b  = Integer::sum;         // (T,T) -> T
```

---

## Pattern / Regex (java.util.regex)

### Pattern & Matcher Basics
```java
// Compile once, reuse — Pattern is thread-safe
Pattern pattern = Pattern.compile("\\d{3}-\\d{4}");
Matcher matcher = pattern.matcher("call 555-1234 now");

// Quick full-string match (no compile needed)
boolean ok = Pattern.matches("\\d+", "12345");  // true

// find() — iterate through all matches
while (matcher.find()) {
    matcher.group();   // "555-1234" — the matched substring
    matcher.start();   // 5  — start index (inclusive)
    matcher.end();     // 13 — end index (exclusive)
}

// matches() — full-string match on the matcher
Pattern.compile("\\d+").matcher("123").matches();  // true

// replaceAll / replaceFirst
String result = pattern.matcher(input).replaceAll("***");
String first  = pattern.matcher(input).replaceFirst("***");

// reset — reuse matcher on new input
matcher.reset("other 999-0000 text");
```

### Named Groups
```java
Pattern p = Pattern.compile("(?<area>\\d{3})-(?<num>\\d{4})");
Matcher m = p.matcher("555-1234");
if (m.find()) {
    m.group("area");   // "555"
    m.group("num");    // "1234"
    m.group(1);        // "555" — by index (1-based)
    m.group(0);        // "555-1234" — entire match
}
```

### Common Regex Patterns
```java
// Email (simplified)
Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}$");

// URL
Pattern URL = Pattern.compile("^https?://[\\w.-]+(?:/[\\w./?%&=+-]*)?$");

// Integer or decimal number
Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");

// ISO date YYYY-MM-DD
Pattern DATE = Pattern.compile("^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$");

// IPv4
Pattern IPV4 = Pattern.compile(
    "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
```

### Predicate from Pattern (Java 11+)
```java
Pattern digits = Pattern.compile("\\d+");

// asPredicate — partial match (find)
Predicate<String> hasDigits = digits.asPredicate();
hasDigits.test("abc123");  // true — contains digits

// asMatchPredicate — full-string match (matches)
Predicate<String> allDigits = digits.asMatchPredicate();
allDigits.test("abc123");  // false — not entirely digits
allDigits.test("123");     // true

// Stream filtering
List<String> nums = lines.stream()
    .filter(digits.asPredicate())
    .toList();
```

---

## Date/Time (java.time) — Java 8+

### Core Types
```java
// LocalDate — date without time or timezone
LocalDate today    = LocalDate.now();                 // 2026-02-19
LocalDate specific = LocalDate.of(2025, 3, 15);      // 2025-03-15
LocalDate parsed   = LocalDate.parse("2025-03-15");  // ISO format

// LocalTime — time without date or timezone
LocalTime now      = LocalTime.now();                 // 14:30:59.123
LocalTime fixed    = LocalTime.of(14, 30);            // 14:30
LocalTime withSec  = LocalTime.of(14, 30, 45);       // 14:30:45

// LocalDateTime — date + time, no timezone
LocalDateTime ldt  = LocalDateTime.now();
LocalDateTime spec = LocalDateTime.of(2025, 3, 15, 14, 30);
LocalDateTime comb = LocalDateTime.of(today, now);

// ZonedDateTime — date + time + timezone
ZonedDateTime zdt  = ZonedDateTime.now(ZoneId.of("America/New_York"));
ZonedDateTime utc  = ZonedDateTime.now(ZoneOffset.UTC);

// Instant — machine timestamp (epoch-based)
Instant instant = Instant.now();                       // UTC timestamp
long epochSec   = instant.getEpochSecond();
long epochMilli = instant.toEpochMilli();
Instant fromMs  = Instant.ofEpochMilli(1700000000000L);
```

### Common Operations
```java
// Arithmetic
LocalDate tomorrow = today.plusDays(1);
LocalDate lastWeek = today.minusWeeks(1);
LocalDateTime later = ldt.plusHours(3).plusMinutes(30);

// Comparison
today.isBefore(tomorrow);   // true
today.isAfter(lastWeek);    // true
today.isEqual(today);       // true

// Between
long daysBetween   = ChronoUnit.DAYS.between(date1, date2);
long hoursBetween  = ChronoUnit.HOURS.between(time1, time2);
Period period       = Period.between(date1, date2);  // years/months/days
Duration duration   = Duration.between(time1, time2); // hours/mins/secs

// Extracting fields
today.getYear();  today.getMonth();  today.getDayOfMonth();
today.getDayOfWeek();  // DayOfWeek.WEDNESDAY
today.getDayOfYear();
```

### Duration & Period
```java
// Duration — time-based (hours, minutes, seconds, nanos)
Duration d1 = Duration.ofHours(2);
Duration d2 = Duration.ofMinutes(30);
Duration d3 = Duration.between(startTime, endTime);
d1.toMinutes();  // 120
d1.plus(d2);     // 2h 30m

// Period — date-based (years, months, days)
Period p1 = Period.ofDays(10);
Period p2 = Period.ofMonths(3);
Period p3 = Period.between(startDate, endDate);
p3.getYears();  p3.getMonths();  p3.getDays();
```

### DateTimeFormatter
```java
// Built-in formatters
String iso = today.format(DateTimeFormatter.ISO_LOCAL_DATE);  // "2026-02-19"

// Custom pattern
DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
String text = ldt.format(fmt);                      // "02/19/2026 14:30"
LocalDateTime back = LocalDateTime.parse(text, fmt); // roundtrip

// Common patterns
DateTimeFormatter.ofPattern("yyyy-MM-dd");           // 2026-02-19
DateTimeFormatter.ofPattern("dd MMM yyyy");          // 19 Feb 2026
DateTimeFormatter.ofPattern("HH:mm:ss");             // 14:30:59
DateTimeFormatter.ofPattern("EEE, dd MMM yyyy");     // Thu, 19 Feb 2026
```

### TemporalAdjusters
```java
import java.time.temporal.TemporalAdjusters;

today.with(TemporalAdjusters.firstDayOfMonth());     // 2026-02-01
today.with(TemporalAdjusters.lastDayOfMonth());       // 2026-02-28
today.with(TemporalAdjusters.firstDayOfYear());       // 2026-01-01
today.with(TemporalAdjusters.lastDayOfYear());        // 2026-12-31
today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)); // next Monday
today.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
today.with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
```

### Converting Legacy Date/Instant
```java
// java.util.Date <-> Instant
Instant inst = new Date().toInstant();
Date date    = Date.from(inst);

// java.util.Date <-> LocalDateTime
LocalDateTime ldt = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
Date back = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());

// java.sql.Timestamp <-> Instant / LocalDateTime
Timestamp ts     = Timestamp.from(Instant.now());
Timestamp ts2    = Timestamp.valueOf(LocalDateTime.now());
LocalDateTime ld = ts.toLocalDateTime();
Instant i        = ts.toInstant();
```

---

## HttpClient (java.net.http) — Java 11+

### Creating a Client
```java
// Simple default client
HttpClient client = HttpClient.newHttpClient();

// Customized client
HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```

### Building Requests
```java
// GET request
HttpRequest getReq = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .header("Accept", "application/json")
    .header("Authorization", "Bearer token123")
    .GET()                              // GET is the default
    .timeout(Duration.ofSeconds(30))
    .build();

// POST with JSON body
HttpRequest postReq = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/items"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"item\"}"))
    .build();

// PUT / DELETE
HttpRequest putReq = HttpRequest.newBuilder(URI.create(url))
    .PUT(HttpRequest.BodyPublishers.ofString(body))
    .build();

HttpRequest delReq = HttpRequest.newBuilder(URI.create(url))
    .DELETE()
    .build();
```

### Sending Requests — Sync & Async
```java
// Synchronous — blocks the calling thread
HttpResponse<String> resp = client.send(getReq,
    HttpResponse.BodyHandlers.ofString());

resp.statusCode();   // 200
resp.body();         // response body as String
resp.headers().map(); // Map<String, List<String>>

// Asynchronous — returns CompletableFuture
CompletableFuture<HttpResponse<String>> future =
    client.sendAsync(getReq, HttpResponse.BodyHandlers.ofString());

future.thenAccept(r -> System.out.println(r.body()));
```

### BodyHandlers (response)
```java
HttpResponse.BodyHandlers.ofString();          // -> String
HttpResponse.BodyHandlers.ofFile(Path.of("out.json")); // -> Path
HttpResponse.BodyHandlers.ofInputStream();     // -> InputStream
HttpResponse.BodyHandlers.ofByteArray();       // -> byte[]
HttpResponse.BodyHandlers.ofLines();           // -> Stream<String>
HttpResponse.BodyHandlers.discarding();        // ignore body -> Void
```

### BodyPublishers (request)
```java
HttpRequest.BodyPublishers.ofString("text");         // from String
HttpRequest.BodyPublishers.ofFile(Path.of("data.json")); // from file
HttpRequest.BodyPublishers.ofByteArray(bytes);       // from byte[]
HttpRequest.BodyPublishers.ofInputStream(() -> is);  // from InputStream
HttpRequest.BodyPublishers.noBody();                 // empty body (DELETE)
```

---

## Exceptions & Error Handling

### Exception Hierarchy
```
Throwable
├── Error (unrecoverable — do not catch)
│   ├── OutOfMemoryError
│   ├── StackOverflowError
│   └── VirtualMachineError
└── Exception (recoverable)
    ├── IOException              (checked)
    ├── SQLException             (checked)
    ├── ClassNotFoundException   (checked)
    ├── InterruptedException     (checked)
    ├── ReflectiveOperationException (checked)
    └── RuntimeException         (unchecked)
        ├── NullPointerException
        ├── IllegalArgumentException
        │   └── NumberFormatException
        ├── IllegalStateException
        ├── IndexOutOfBoundsException
        │   ├── ArrayIndexOutOfBoundsException
        │   └── StringIndexOutOfBoundsException
        ├── ClassCastException
        ├── UnsupportedOperationException
        ├── ConcurrentModificationException
        └── ArithmeticException
```

### try-catch-finally & Multi-catch
```java
// Standard try-catch-finally
try {
    riskyOperation();
} catch (IOException e) {
    log.error("IO failed", e);
} finally {
    cleanup();  // always runs
}

// Multi-catch — single block for multiple exception types
try {
    parseAndStore(input);
} catch (IOException | ParseException e) {
    // e is effectively final here
    throw new ServiceException("Failed to process", e);
} catch (SQLException e) {
    handleDbError(e);
}

// Helpful NullPointerException messages (Java 14+)
// "Cannot invoke String.length() because the return value of getUser().getName() is null"
```

### Custom Exception Patterns
```java
// Custom checked exception
public class ServiceException extends Exception {
    private final int errorCode;
    public ServiceException(String message, int code) {
        super(message);
        this.errorCode = code;
    }
    public ServiceException(String message, Throwable cause) {
        super(message, cause);  // preserve original stack trace
    }
    public int getErrorCode() { return errorCode; }
}

// Custom unchecked exception
public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String entity, Object id) {
        super("%s not found with id: %s".formatted(entity, id));
    }
}

// Throwing and rethrowing
throw new IllegalArgumentException("id must be positive: " + id);
throw new ServiceException("lookup failed", originalException);
```

---

## Annotations

### Built-in Annotations
```java
@Override                   // compile error if method doesn't override
void toString() { ... }

@Deprecated(since = "11", forRemoval = true)  // mark as obsolete
void oldMethod() { ... }

@SuppressWarnings("unchecked")   // silence compiler warnings
@SuppressWarnings({"unchecked", "deprecation"})

@FunctionalInterface        // enforce single abstract method
interface Converter<F, T> { T convert(F from); }

@SafeVarargs               // suppress heap-pollution warnings on varargs
final <T> void process(T... items) { ... }
```

### Meta-Annotations
```java
@Target(ElementType.METHOD)              // where it can be applied
// ElementType: TYPE, FIELD, METHOD, PARAMETER, CONSTRUCTOR, LOCAL_VARIABLE,
//              ANNOTATION_TYPE, PACKAGE, TYPE_PARAMETER, TYPE_USE, MODULE

@Retention(RetentionPolicy.RUNTIME)      // when it's available
// RetentionPolicy: SOURCE (compiler only), CLASS (in .class), RUNTIME (reflection)

@Documented                              // included in Javadoc
@Inherited                               // subclasses inherit it
@Repeatable(Schedules.class)             // can be applied multiple times
```

### Custom Annotation Declaration
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {
    int maxAttempts() default 3;
    long delayMs() default 1000;
    Class<? extends Throwable>[] retryOn() default {Exception.class};
}

// Usage
@Retry(maxAttempts = 5, delayMs = 500)
public void unreliableCall() { ... }

// Reading at runtime via reflection
Method m = MyClass.class.getMethod("unreliableCall");
if (m.isAnnotationPresent(Retry.class)) {
    Retry r = m.getAnnotation(Retry.class);
    int max = r.maxAttempts();  // 5
}
```

### Common Framework Annotations
```java
// JUnit 5
@Test                       // marks a test method
@BeforeEach / @AfterEach   // run before/after each test
@BeforeAll / @AfterAll     // run once before/after all tests (static)
@DisplayName("...")        // custom test name
@ParameterizedTest         // parameterized test
@Disabled("reason")        // skip test

// Spring / Jakarta
@Autowired                 // dependency injection (Spring)
@Inject                    // dependency injection (Jakarta CDI / JSR-330)
@Component / @Service / @Repository  // stereotype annotations
@RestController            // REST endpoint class
@RequestMapping("/path")   // URL mapping
@Transactional             // transaction boundary

// General
@Nullable / @NonNull       // null-safety hints (javax.annotation / jetbrains)
@Generated                 // marks generated code
```

---

## Concurrency (java.util.concurrent)

### ExecutorService
```java
// Thread pool creation
ExecutorService pool = Executors.newFixedThreadPool(4);      // fixed size
ExecutorService cached = Executors.newCachedThreadPool();     // grows as needed
ExecutorService single = Executors.newSingleThreadExecutor(); // serial

// Submitting tasks
Future<String> future = pool.submit(() -> fetchData());      // Callable
pool.submit(() -> doWork());                                  // Runnable
pool.execute(() -> fireAndForget());                          // no Future

// Getting results
String result = future.get();                    // blocks until done
String result = future.get(5, TimeUnit.SECONDS); // blocks with timeout
future.isDone();  future.isCancelled();  future.cancel(true);

// Batch execution
List<Callable<String>> tasks = List.of(() -> "a", () -> "b");
List<Future<String>> results = pool.invokeAll(tasks);        // all complete
String fastest = pool.invokeAny(tasks);                       // first to finish

// Shutdown
pool.shutdown();                              // no new tasks, finish existing
pool.awaitTermination(60, TimeUnit.SECONDS);  // wait for completion
pool.shutdownNow();                           // interrupt running tasks
```

### ScheduledExecutorService
```java
ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

// Run once after delay
sched.schedule(() -> cleanup(), 5, TimeUnit.SECONDS);

// Run periodically (fixed rate — from start of each run)
sched.scheduleAtFixedRate(() -> poll(), 0, 10, TimeUnit.SECONDS);

// Run with fixed delay between end of one run and start of next
sched.scheduleWithFixedDelay(() -> sync(), 0, 30, TimeUnit.SECONDS);
```

### Synchronization Utilities
```java
// CountDownLatch — one-time barrier; threads wait until count reaches zero
CountDownLatch latch = new CountDownLatch(3);
latch.countDown();               // decrement by 1
latch.await();                   // block until count == 0
latch.await(5, TimeUnit.SECONDS); // block with timeout

// CyclicBarrier — reusable barrier; N threads wait for each other
CyclicBarrier barrier = new CyclicBarrier(3, () -> mergeResults());
barrier.await();                 // block until all parties arrive

// Semaphore — limits concurrent access to a resource
Semaphore sem = new Semaphore(5);  // 5 permits
sem.acquire();                     // take a permit (blocks if none)
sem.release();                     // return a permit
sem.tryAcquire(1, TimeUnit.SECONDS); // non-blocking with timeout
```

### ConcurrentHashMap
```java
ConcurrentHashMap<String, Integer> cmap = new ConcurrentHashMap<>();
cmap.put("a", 1);
cmap.putIfAbsent("a", 2);          // only if key absent, returns existing
cmap.computeIfAbsent("b", k -> 42); // lazy compute if absent
cmap.compute("a", (k, v) -> v == null ? 1 : v + 1); // atomic update
cmap.merge("a", 1, Integer::sum);  // atomic upsert

// Bulk operations (parallel with threshold)
cmap.forEach(1, (k, v) -> System.out.println(k + "=" + v));
cmap.reduce(1, (k, v) -> v, Integer::sum);  // parallel reduce
cmap.search(1, (k, v) -> v > 10 ? k : null); // parallel search
```

### Atomic Variables
```java
AtomicInteger counter = new AtomicInteger(0);
counter.get();                     // read
counter.set(10);                   // write
counter.incrementAndGet();         // ++x (returns new value)
counter.getAndIncrement();         // x++ (returns old value)
counter.addAndGet(5);              // += 5
counter.compareAndSet(15, 20);     // CAS: if 15, set to 20, return true
counter.updateAndGet(n -> n * 2);  // atomic transformation

AtomicReference<String> ref = new AtomicReference<>("initial");
ref.get();  ref.set("new");
ref.compareAndSet("new", "updated");
ref.updateAndGet(s -> s.toUpperCase());
```

### ReentrantLock & ReadWriteLock
```java
// ReentrantLock — explicit locking (more flexible than synchronized)
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();  // always unlock in finally
}
lock.tryLock(1, TimeUnit.SECONDS);  // non-blocking with timeout

// ReadWriteLock — multiple readers OR single writer
ReadWriteLock rwLock = new ReentrantReadWriteLock();
rwLock.readLock().lock();    // shared — multiple threads can read
rwLock.readLock().unlock();
rwLock.writeLock().lock();   // exclusive — one thread can write
rwLock.writeLock().unlock();
```

### Virtual Threads (Java 21+)
```java
// Start a virtual thread directly
Thread vt = Thread.ofVirtual().start(() -> doWork());
Thread named = Thread.ofVirtual().name("worker").start(() -> doWork());

// Factory for virtual threads
ThreadFactory factory = Thread.ofVirtual().name("vt-", 0).factory();

// ExecutorService backed by virtual threads (one per task)
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000).forEach(i ->
        executor.submit(() -> handleRequest(i))
    );
}  // auto-shutdown with try-with-resources

// Check if running on virtual thread
Thread.currentThread().isVirtual();  // true for virtual threads
```

---

## Arrays (java.util.Arrays)

```java
int[] arr = {5, 3, 1, 4, 2};

// Sorting
Arrays.sort(arr);                        // [1, 2, 3, 4, 5] — in-place
Arrays.sort(arr, 1, 4);                  // sort range [1, 4)
String[] names = {"c", "a", "b"};
Arrays.sort(names);                      // natural order
Arrays.sort(names, Comparator.reverseOrder()); // custom comparator

// Searching (array must be sorted first)
int idx = Arrays.binarySearch(arr, 3);   // index of 3 (or negative)

// Copying
int[] copy = Arrays.copyOf(arr, arr.length);       // exact copy
int[] bigger = Arrays.copyOf(arr, 10);              // padded with 0s
int[] slice = Arrays.copyOfRange(arr, 1, 4);        // [2, 3, 4]

// Fill
Arrays.fill(arr, 0);                     // all elements set to 0
Arrays.fill(arr, 1, 3, 99);              // range fill

// Comparison
Arrays.equals(arr1, arr2);               // shallow (1D) comparison
Arrays.deepEquals(arr2d_1, arr2d_2);     // deep (nested array) comparison
int pos = Arrays.mismatch(arr1, arr2);   // first differing index, -1 if equal

// Conversion
List<String> list = Arrays.asList(names);           // fixed-size list backed by array
IntStream stream = Arrays.stream(arr);               // stream from array
IntStream partial = Arrays.stream(arr, 1, 4);        // stream from range
String text = Arrays.toString(arr);                   // "[1, 2, 3, 4, 5]"
String deep = Arrays.deepToString(matrix);            // nested arrays
```

---

## Map.Entry & Comparator

### Map.Entry Comparators
```java
// Sort map entries by key
map.entrySet().stream()
    .sorted(Map.Entry.comparingByKey())
    .forEach(e -> System.out.println(e.getKey() + "=" + e.getValue()));

// Sort map entries by value (descending)
map.entrySet().stream()
    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
    .toList();

// With custom comparator on keys/values
Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER);
Map.Entry.comparingByValue(Comparator.reverseOrder());
```

### Comparator
```java
// Comparing by a key extractor
Comparator<Person> byAge  = Comparator.comparing(Person::age);
Comparator<Person> byName = Comparator.comparing(Person::name);

// Chaining: primary sort, then secondary
Comparator<Person> comp = Comparator
    .comparing(Person::lastName)
    .thenComparing(Person::firstName)
    .thenComparingInt(Person::age);         // primitive-friendly

// Reversing
Comparator<Person> youngest = byAge.reversed();

// Natural order / reverse
Comparator<String> nat = Comparator.naturalOrder();    // a, b, c
Comparator<String> rev = Comparator.reverseOrder();    // c, b, a

// Null handling
Comparator<String> nullFirst = Comparator.nullsFirst(Comparator.naturalOrder());
Comparator<String> nullLast  = Comparator.nullsLast(Comparator.naturalOrder());

// Usage in sorting
list.sort(Comparator.comparing(Person::name));
Collections.sort(list, Comparator.comparingInt(Person::age).reversed());
```

---

## System & Runtime

### System
```java
// Environment variables
String home = System.getenv("HOME");           // single variable
Map<String, String> env = System.getenv();     // all variables

// System properties
String javaVer = System.getProperty("java.version");  // e.g. "21.0.1"
String osName  = System.getProperty("os.name");        // e.g. "Linux"
String userDir = System.getProperty("user.dir");       // working directory
System.setProperty("key", "value");

// Timing
long startMs = System.currentTimeMillis();     // epoch millis
long startNs = System.nanoTime();              // high-resolution (relative)
// ... work ...
long elapsedMs = System.currentTimeMillis() - startMs;
long elapsedNs = System.nanoTime() - startNs;

// Exit
System.exit(0);   // normal exit (0 = success, non-zero = error)
```

### Runtime
```java
Runtime rt = Runtime.getRuntime();

rt.availableProcessors();   // number of CPU cores
rt.freeMemory();            // free memory in JVM (bytes)
rt.totalMemory();           // total memory allocated to JVM (bytes)
rt.maxMemory();             // max memory JVM can use (bytes)
rt.gc();                    // suggest garbage collection (not guaranteed)

// Shutdown hook
rt.addShutdownHook(new Thread(() -> {
    System.out.println("JVM shutting down — cleanup here");
}));
```

### ProcessBuilder — External Command Execution
```java
// Simple command
ProcessBuilder pb = new ProcessBuilder("ls", "-la", "/tmp");
pb.directory(new File("/home/user"));          // working directory
pb.redirectErrorStream(true);                  // merge stderr into stdout

Process process = pb.start();
String output = new String(process.getInputStream().readAllBytes());
int exitCode = process.waitFor();              // blocks until done

// With environment variables
pb.environment().put("MY_VAR", "value");

// Piping output to file
pb.redirectOutput(new File("output.txt"));
pb.redirectError(new File("error.txt"));

// With timeout
boolean finished = process.waitFor(30, TimeUnit.SECONDS);
if (!finished) process.destroyForcibly();

// Quick one-liner (Java 9+ convenience)
Process p = new ProcessBuilder("echo", "hello").inheritIO().start();
p.waitFor();
```

---

## Generics

### Generic Classes & Methods
```java
// Generic class
public class Box<T> {
    private T value;
    public Box(T value) { this.value = value; }
    public T get() { return value; }
    public void set(T value) { this.value = value; }
}
Box<String> box = new Box<>("hello");

// Generic method
public static <T> List<T> singletonList(T item) {
    return List.of(item);
}
List<String> list = singletonList("hello");  // type inferred

// Multiple type parameters
public class Pair<K, V> {
    private final K key;
    private final V value;
    public Pair(K key, V value) { this.key = key; this.value = value; }
    public K getKey() { return key; }
    public V getValue() { return value; }
}
```

### Bounded Type Parameters
```java
// Upper bound — T must implement Comparable
public static <T extends Comparable<T>> T max(T a, T b) {
    return a.compareTo(b) >= 0 ? a : b;
}

// Multiple bounds — T must extend Number AND implement Comparable
public static <T extends Number & Comparable<T>> T clamp(T val, T min, T max) {
    if (val.compareTo(min) < 0) return min;
    if (val.compareTo(max) > 0) return max;
    return val;
}
```

### Wildcards
```java
// Upper-bounded wildcard — read-only (producer)
// "anything that IS-A Number" — can read Number out, cannot put in
public double sum(List<? extends Number> nums) {
    return nums.stream().mapToDouble(Number::doubleValue).sum();
}
sum(List.of(1, 2, 3));           // List<Integer> — OK
sum(List.of(1.5, 2.5));          // List<Double> — OK

// Lower-bounded wildcard — write-only (consumer)
// "anything that is a SUPERTYPE of Integer" — can put Integer in
public void addNumbers(List<? super Integer> list) {
    list.add(1);
    list.add(2);
}
addNumbers(new ArrayList<Number>());  // OK
addNumbers(new ArrayList<Object>());  // OK

// Unbounded wildcard — neither read typed nor write
public void printAll(List<?> list) {
    for (Object item : list) System.out.println(item);
}

// PECS: Producer Extends, Consumer Super
public static <T> void copy(List<? extends T> src, List<? super T> dst) {
    for (T item : src) dst.add(item);
}
```

### Type Erasure
```java
// At runtime, generic types are erased:
//   List<String> and List<Integer> are both just List
//   T becomes Object (or its upper bound)
//
// Implications:
//   - Cannot use: new T(), new T[], instanceof List<String>
//   - Can use:    instanceof List<?>, instanceof List (raw)
//   - Generic arrays not directly creatable: use Array.newInstance or List
//
// Workaround — pass Class<T> for runtime type info:
public <T> T create(Class<T> clazz) throws Exception {
    return clazz.getDeclaredConstructor().newInstance();
}
```
