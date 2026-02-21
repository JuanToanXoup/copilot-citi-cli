# Mermaid Syntax Reference

Mermaid is a JavaScript-based diagramming tool that renders text definitions into diagrams. All definitions begin with a diagram type declaration inside a fenced code block.

````markdown
```mermaid
flowchart LR
    A --> B
```
````

Comments use `%%`. Unknown words break diagrams. The word `end` is reserved — wrap it in quotes if needed as a label.

## Diagram Types

| Keyword | Diagram |
|---------|---------|
| `flowchart` or `graph` | Flowchart |
| `sequenceDiagram` | Sequence diagram |
| `classDiagram` | Class diagram |
| `stateDiagram-v2` | State diagram |
| `erDiagram` | Entity relationship diagram |
| `gantt` | Gantt chart |
| `pie` | Pie chart |
| `gitGraph` | Git graph |
| `journey` | User journey map |
| `mindmap` | Mind map |
| `timeline` | Timeline |
| `sankey-beta` | Sankey diagram |
| `xychart-beta` | XY chart |
| `block-beta` | Block diagram |
| `packet-beta` | Packet diagram |
| `kanban` | Kanban board |
| `architecture-beta` | Architecture diagram |

## Flowchart

### Direction

| Keyword | Direction |
|---------|-----------|
| `TB` / `TD` | Top to bottom |
| `BT` | Bottom to top |
| `LR` | Left to right |
| `RL` | Right to left |

```mermaid
flowchart LR
    A --> B --> C
```

### Node Shapes

| Syntax | Shape |
|--------|-------|
| `id` | Default rectangle |
| `id[text]` | Rectangle with text |
| `id(text)` | Rounded edges |
| `id([text])` | Stadium / pill |
| `id[[text]]` | Subroutine |
| `id[(text)]` | Cylinder / database |
| `id((text))` | Circle |
| `id{text}` | Diamond / decision |
| `id{{text}}` | Hexagon |
| `id[/text/]` | Parallelogram |
| `id[\text\]` | Parallelogram (reversed) |
| `id[/text\]` | Trapezoid |
| `id[\text/]` | Trapezoid (reversed) |
| `id(((text)))` | Double circle |
| `id>text]` | Asymmetric / flag |

### Edge Types

| Syntax | Description |
|--------|-------------|
| `A --> B` | Arrow |
| `A --- B` | Open link (no arrow) |
| `A -.-> B` | Dotted arrow |
| `A ==> B` | Thick arrow |
| `A ~~~ B` | Invisible link |
| `A --o B` | Circle endpoint |
| `A --x B` | Cross endpoint |
| `A <--> B` | Bidirectional arrow |

Text on edges: `A -->|text| B` or `A -- text --> B`

Longer links: add extra dashes `A ----> B`, dots `A -.....-> B`, or equals `A ====> B`.

### Subgraphs

```mermaid
flowchart TB
    subgraph sub1 [Subsystem A]
        A1 --> A2
    end
    subgraph sub2 [Subsystem B]
        B1 --> B2
    end
    sub1 --> sub2
```

Subgraphs can set their own direction with `direction LR` inside the block. Edges can connect subgraph IDs to nodes.

### Styling

```mermaid
flowchart LR
    A:::highlight --> B
    classDef highlight fill:#f9f,stroke:#333,stroke-width:2px
    style B fill:#bbf,stroke:#333
```

- `classDef name prop:val,prop:val` — define a class
- `class nodeId className` or `nodeId:::className` — apply a class
- `style nodeId prop:val` — inline style on a single node
- `linkStyle 0 stroke:#ff3,stroke-width:4px` — style edge by index (0-based)

## Sequence Diagram

### Participants

```mermaid
sequenceDiagram
    participant A as Alice
    actor B as Bob
    A ->> B: Hello Bob
    B -->> A: Hi Alice
```

`participant` draws a box, `actor` draws a stick figure. Participants render in declaration order.

### Message Arrows

| Syntax | Description |
|--------|-------------|
| `->` | Solid line, no arrowhead |
| `-->` | Dashed line, no arrowhead |
| `->>` | Solid line with arrowhead |
| `-->>` | Dashed line with arrowhead |
| `-x` | Solid line with cross |
| `--x` | Dashed line with cross |
| `-)` | Solid line with open arrow (async) |
| `--)` | Dashed line with open arrow (async) |

### Activations

```mermaid
sequenceDiagram
    Alice ->>+ Bob: Request
    Bob -->>- Alice: Response
```

Use `+` after arrow to activate, `-` to deactivate. Or use explicit `activate`/`deactivate` keywords. Activations can nest (stack).

### Notes

```
Note right of Alice: Single participant
Note over Alice,Bob: Spanning note
Note left of Bob: Left side
```

### Control Flow

```mermaid
sequenceDiagram
    Alice ->> Bob: Request
    alt success
        Bob -->> Alice: 200 OK
    else failure
        Bob -->> Alice: 500 Error
    end

    opt optional step
        Alice ->> Bob: Follow-up
    end

    loop every minute
        Alice ->> Bob: Heartbeat
    end

    par parallel
        Alice ->> Bob: Msg 1
    and
        Alice ->> Charlie: Msg 2
    end

    critical critical section
        Alice ->> Bob: Important
    option timeout
        Alice ->> Bob: Retry
    end

    break when condition met
        Alice -->> Bob: Abort
    end
```

### Other Features

- `autonumber` — auto-number messages
- `create participant C` — create mid-diagram
- `destroy C` — remove participant
- `box rgba(100,100,100,0.1) Group Name` ... `end` — visual grouping
- `rect rgb(200,200,200)` ... `end` — background highlight
- `<br/>` — line break in messages/notes

## Class Diagram

```mermaid
classDiagram
    class Animal {
        +String name
        +int age
        +makeSound()* void
    }
    class Dog {
        +fetch() void
    }
    Animal <|-- Dog : extends
```

### Visibility

| Symbol | Meaning |
|--------|---------|
| `+` | Public |
| `-` | Private |
| `#` | Protected |
| `~` | Package/Internal |

### Method Classifiers

- `*` after `()` — abstract
- `$` after `()` — static

### Relationships

| Syntax | Type |
|--------|------|
| `A <\|-- B` | Inheritance |
| `A *-- B` | Composition |
| `A o-- B` | Aggregation |
| `A --> B` | Association |
| `A -- B` | Solid link |
| `A ..> B` | Dependency |
| `A ..\|> B` | Realization |
| `A .. B` | Dashed link |

Labels: `A <|-- B : implements`

Cardinality: `A "1" --> "*" B`

### Annotations

```
class Shape {
    <<Interface>>
}
class Color {
    <<Enumeration>>
    RED
    GREEN
    BLUE
}
```

### Namespaces

```mermaid
classDiagram
    namespace com.example {
        class UserService
        class UserRepo
    }
```

### Direction

`direction RL` at the top of the diagram. Supports `TB`, `BT`, `LR`, `RL`.

## State Diagram

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Processing : submit
    Processing --> Done : complete
    Processing --> Error : fail
    Error --> Idle : retry
    Done --> [*]
```

- `[*]` — start state (when target) or end state (when source)
- `s1 --> s2 : label` — transition with label
- `state "Description" as s1` — state with description
- `s1 : Description` — alternative description syntax

### Composite States

```mermaid
stateDiagram-v2
    state Active {
        [*] --> Running
        Running --> Paused : pause
        Paused --> Running : resume
    }
    [*] --> Active
    Active --> [*] : done
```

### Fork / Join (Concurrency)

```mermaid
stateDiagram-v2
    state fork_state <<fork>>
    state join_state <<join>>
    [*] --> fork_state
    fork_state --> State2
    fork_state --> State3
    State2 --> join_state
    State3 --> join_state
    join_state --> [*]
```

### Choice (Conditional)

```mermaid
stateDiagram-v2
    state check <<choice>>
    [*] --> check
    check --> Valid : if valid
    check --> Invalid : if invalid
```

### Concurrency (Parallel Regions)

```
state Active {
    [*] --> A
    --
    [*] --> B
}
```

The `--` separator creates parallel regions within a composite state.

### Notes

```
note right of State1 : explanation
note left of State2
    Multi-line
    note text
end note
```

### Styling

```
classDef highlight fill:#f00,color:#fff
class State1 highlight
```

Or inline: `State1:::highlight`

## Entity Relationship Diagram

```mermaid
erDiagram
    CUSTOMER ||--o{ ORDER : places
    ORDER ||--|{ LINE_ITEM : contains
    PRODUCT ||--o{ LINE_ITEM : "is in"
```

### Cardinality (Crow's Foot)

| Notation | Meaning |
|----------|---------|
| `\|o` | Zero or one |
| `\|\|` | Exactly one |
| `}o` | Zero or more (many) |
| `}\|` | One or more |

Left side is first entity's cardinality, right side is second entity's.

### Relationship Lines

- `--` — identifying (solid line)
- `..` — non-identifying (dashed line)

### Attributes

```mermaid
erDiagram
    USER {
        int id PK
        string username UK
        string email
        int role_id FK
    }
    ROLE {
        int id PK
        string name
    }
    ROLE ||--o{ USER : "has"
```

Format: `type name [PK|FK|UK] ["comment"]`

### Direction

`direction LR` or `direction TB` etc. at the start.

## Gantt Chart

```mermaid
gantt
    title Project Plan
    dateFormat YYYY-MM-DD
    excludes weekends

    section Design
    Wireframes      :a1, 2024-01-01, 7d
    Mockups         :a2, after a1, 5d

    section Development
    Backend API     :b1, after a1, 14d
    Frontend        :b2, after a2, 14d

    section Testing
    Integration     :after b1, 7d
```

### Task Syntax

```
taskName :id, startDate, duration
taskName :id, after otherId, duration
taskName :active, 2024-01-01, 3d
taskName :done, 2024-01-01, 2d
taskName :crit, 2024-01-10, 5d
taskName :milestone, 2024-01-15, 0d
```

Tags: `done`, `active`, `crit` (critical), `milestone`. Combine: `crit, active, 2024-01-01, 3d`.

### Date Formats

`dateFormat YYYY-MM-DD` — set input format. Supports moment.js tokens.

`axisFormat %Y-%m` — set axis display format (d3 time format).

`tickInterval 1week` — control axis tick spacing.

### Excludes

```
excludes weekends
excludes 2024-12-25, 2024-01-01
```

## Pie Chart

```mermaid
pie title Pets
    "Dogs" : 40
    "Cats" : 30
    "Birds" : 20
    "Fish" : 10
```

Optional `showData` after `pie` to display values.

## Git Graph

```mermaid
gitGraph
    commit
    commit
    branch develop
    checkout develop
    commit
    commit
    checkout main
    merge develop
    commit
```

### Commands

| Command | Description |
|---------|-------------|
| `commit` | Add a commit |
| `commit id: "msg"` | Commit with label |
| `commit tag: "v1.0"` | Commit with tag |
| `commit type: HIGHLIGHT` | Types: `NORMAL`, `REVERSE`, `HIGHLIGHT` |
| `branch name` | Create branch |
| `checkout name` | Switch to branch |
| `merge name` | Merge branch into current |
| `cherry-pick id: "abc"` | Cherry-pick a commit |

## User Journey

```mermaid
journey
    title User Shopping Experience
    section Browse
        Visit homepage: 5: User
        Search product: 3: User
    section Purchase
        Add to cart: 4: User
        Checkout: 3: User, System
        Payment: 2: User, System
```

Format: `task name: satisfaction(1-5): actors`

## Mindmap

```mermaid
mindmap
    root((Project))
        Planning
            Requirements
            Timeline
        Development
            Frontend
            Backend
        Testing
            Unit
            Integration
```

Node shapes: `(round)`, `[square]`, `((circle))`, `)cloud(`, `{{hexagon}}`. Indentation defines hierarchy.

## Timeline

```mermaid
timeline
    title History
    2020 : Event A : Event B
    2021 : Event C
    2022 : Event D : Event E : Event F
```

Sections group periods: `section Phase Name` before time entries.

## XY Chart

```mermaid
xychart-beta
    title "Sales"
    x-axis [Jan, Feb, Mar, Apr, May]
    y-axis "Revenue" 0 --> 100
    bar [10, 30, 50, 40, 60]
    line [10, 30, 50, 40, 60]
```

## Configuration

### Frontmatter

```mermaid
---
title: My Diagram
config:
    theme: forest
---
flowchart LR
    A --> B
```

### Directives

```
%%{init: {"theme": "dark"}}%%
flowchart LR
    A --> B
```

### Themes

| Theme | Description |
|-------|-------------|
| `default` | Standard colors |
| `dark` | Dark background |
| `forest` | Green palette |
| `neutral` | Grayscale |
| `base` | Minimal, for customization |

### Theme Variables

Override via `themeVariables` in init:

```
%%{init: {"theme": "base", "themeVariables": {"primaryColor": "#ff0000"}}}%%
```

Common variables: `primaryColor`, `primaryTextColor`, `primaryBorderColor`, `lineColor`, `secondaryColor`, `tertiaryColor`, `fontSize`.

## Common Patterns

### Flowchart with Decision

```mermaid
flowchart TD
    Start([Start]) --> Input[/Get input/]
    Input --> Check{Valid?}
    Check -->|Yes| Process[Process data]
    Check -->|No| Error[Show error]
    Error --> Input
    Process --> Done([End])
```

### Service Architecture

```mermaid
flowchart LR
    Client --> LB[Load Balancer]
    LB --> API1[API Server 1]
    LB --> API2[API Server 2]
    API1 --> DB[(Database)]
    API2 --> DB
    API1 --> Cache[(Redis)]
    API2 --> Cache
```

### API Sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant G as API Gateway
    participant S as Service
    participant D as Database

    C ->>+ G: POST /api/resource
    G ->>+ S: Forward request
    S ->>+ D: INSERT
    D -->>- S: OK
    S -->>- G: 201 Created
    G -->>- C: 201 Created
```

### State Machine

```mermaid
stateDiagram-v2
    [*] --> Draft
    Draft --> Review : submit
    Review --> Approved : approve
    Review --> Draft : reject
    Approved --> Published : publish
    Published --> Archived : archive
    Archived --> [*]
```

### Data Model

```mermaid
erDiagram
    USER ||--o{ POST : writes
    USER ||--o{ COMMENT : writes
    POST ||--o{ COMMENT : has
    POST }o--|| CATEGORY : "belongs to"
```
