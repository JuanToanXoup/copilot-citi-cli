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
