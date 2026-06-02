# 🚀 Future Improvements Roadmap

## Overview

Ez a dokumentum a TruHoldem alkalmazás lehetséges továbbfejlesztéseit tartalmazza prioritás szerint rendezve.

---

## 🧪 TODO — Integration-test layer (Testcontainers)

**Status:** deferred (own focused task). The full-context integration tests are intentionally
excluded from the gating build (`pom.xml` Surefire excludes `*IT` and `PokerGameIntegrationTest`).

**Problem:** the heavy `@SpringBootTest` / `*IT` tests (e.g. `TournamentIT`, the two
`PokerGameIntegrationTest` copies) were written for a Testcontainers-managed Postgres that was never
wired up. On the shared in-memory H2 they hit cross-context `create-drop` contamination
(`Table "poker_games" not found (this database is empty)`). The H2 reserved-word DDL blocker
(`hand_history_board.value`) is already fixed, which is why the schema now builds — exposing this
deeper isolation issue.

**Done so far:** a Testcontainers base already exists (`config/IntegrationTestConfig`: Postgres + Redis,
profile `integration`) and the `*IT` tests use it. Phase 5's ownership lease is verified against real
Redis (`service/cluster/TableOwnershipRedisIT`).

**To do:**
- Fix the H2-based `PokerGameIntegrationTest` (both the root-package and `integration`-package copies):
  either move them onto the Testcontainers Postgres base or give each Spring context an isolated H2 DB —
  the `create-drop` cross-context contamination (`Table "poker_games" not found`) is why they're excluded
  in `pom.xml` Surefire today. Remove the duplicate class.
- **Multi-app-instance harness** — ✅ landed: `MultiNodeClusterIT` boots two full **web** app instances
  against one shared Postgres + Redis (cluster mode + routing + takeover on) and asserts (a) cross-node
  ownership exclusivity, (b) an action on the non-owner node is forwarded over real HTTP to the owner and
  applied once, and (c) kill-node takeover. (The ownership lease is also covered by `TableOwnershipRedisIT`.)
  Note: the harness clears `spring.autoconfigure.exclude`, enables cluster flags via command-line args, and
  boots node-A with `ddl-auto=create` (not `create-drop`) so the schema survives the kill-node test.
- **Cross-node command routing** — ✅ landed (REST + WS): `app.cluster.routing-enabled` forwards an action
  to the owning node over HTTP (`ClusterActionForwarder` → `/internal/cluster/...`). Both REST and WebSocket
  actions route, since both call `PokerGameService.playerAct`.
- **Failover takeover** — ✅ landed: `app.cluster.takeover-enabled` — `ClusterFailoverService` scans the
  `truholdem:cluster:tables` active set and re-acquires + resumes any table whose owner died, instead of
  recovering only on the next action. Resumes **both** the in-progress turn timer and the between-hands
  transition (`HAND_COMPLETED`/`RESULT_DELAY` → next hand, via `GameHandLifecycleService.resumePendingTransition`).
- **Split-brain safety (fail-closed)** — ✅ landed: `app.cluster.fail-closed` makes a node that cannot reach
  Redis refuse ownership (`acquire`/`isOwner` → false) instead of fail-open assuming it owns its tables, so a
  partitioned node stops driving timers / claiming tables. Default off (fail-open) preserves single-node
  availability.
- **Fencing tokens** — ✅ landed: `app.cluster.fencing-enabled` — each lease acquisition carries a monotonic
  token (`truholdem:cluster:fence:{id}`, bumped on ownership change); the Redis hot-state write
  (`RedisGameStateStore.save`) is an atomic compare-and-set that rejects a write whose token is behind the
  current one (`StaleOwnershipException`), fencing out a paused/stale former owner. Postgres is independently
  guarded by `@Version`. Remaining Phase 5 edge-case hardening: recovery of the narrow transient `NEXT_HAND`
  crash window (state persisted but `startNewHandInternal` interrupted mid-flight); multi-hop retry
  orchestration; and a deeper audit of fencing on the Postgres write path (today it relies on `@Version`).
- **Liquibase changelog baseline squash** — ✅ done: `01`–`14` (two tangled schema lineages) were squashed
  into `db/changelog/00-baseline.xml` + `baseline/schema-postgres.sql`, generated from the JPA entities, so
  a fresh Postgres migration matches the entities and `ddl-auto=validate` passes. Old files archived under
  `db/changelog/archive/`; the runnable cluster runs Liquibase on the baseline. The baseline also adds the
  `UNIQUE (player_name)` constraint on `player_statistics` (completing the get-or-create robustness fix).
  Follow-up: the `load-test`/`scale` stack still uses Hibernate `ddl=update` (fine for a throwaway perf
  stack); the `dev` profile likewise. The archived changelogs can eventually be deleted once no environment
  needs them for reference.
- **Crypto wallet (Phase: payments)** — flag-gated skeleton landed (`app.payments.enabled`, default off):
  on-chain deposit credited idempotently by tx id, KYC-gated withdrawals, mock provider + secret-guarded
  webhooks. Remaining to make it production-real:
  - Wire a real `CryptoPaymentProvider` (gateway e.g. NOWPayments/CoinsPaid, or self-custody signer) as a
    `@Primary` bean; configure the webhook secret and verify the gateway's own signature scheme.
  - Wire a real KYC provider (Sumsub/Onfido) → its webhook maps to `recordKycDecision`.
  - Deposit confirmations threshold (credit only after N confirmations) and a **withdrawal-confirmed**
    webhook that moves `WithdrawalRequest` BROADCAST → CONFIRMED (and FAILED → balance reversal entry).
  - For self-custody: persist a deposit address→user mapping (the skeleton allocates an address on demand
    but doesn't store it); add on-chain/AML screening (Chainalysis/Elliptic) on the deposit path.
  - Consider per-asset integer minor units instead of `BigDecimal(38,18)` if exact on-chain parity matters;
    add withdrawal fee handling and rate/limit + AML thresholds. Compliance (gambling + crypto licensing,
    geo-blocking) is an operational prerequisite, not code.
- Re-include the heavy integration tests in `mvnw verify` once green (Docker required in CI).

---

## 🔴 HIGH PRIORITY (Erősen ajánlott)

### 1. WebSocket Real-Time Updates
**Jelenlegi állapot:** HTTP polling
**Cél:** Real-time game state szinkronizáció

**Implementáció:**
```java
// Backend: WebSocketConfig.java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").withSockJS();
    }
}

// GameStatePublisher.java
@Component
public class GameStatePublisher {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    public void publishGameUpdate(UUID gameId, Game game) {
        messagingTemplate.convertAndSend("/topic/game/" + gameId, game);
    }
}
```

**Frontend:**
```typescript
// websocket.service.ts
export class WebSocketService {
  private stompClient: Client;
  
  connect(gameId: string): Observable<Game> {
    return new Observable(observer => {
      this.stompClient.subscribe(`/topic/game/${gameId}`, message => {
        observer.next(JSON.parse(message.body));
      });
    });
  }
}
```

**Előny:** 
- Nincs polling overhead
- Instant UI update
- Skálázhatóbb

**Becsült idő:** 4-6 óra

---

### 2. Advanced Bot AI
**Jelenlegi állapot:** Random döntések egyszerű pot odds-al
**Cél:** Monte Carlo szimulációval és hand range-ekkel

**Implementáció:**
```java
@Service
public class AdvancedBotService {
    
    public PlayerAction decide(Game game, Player bot) {
        double handStrength = calculateHandStrength(bot.getHand(), game.getCommunityCards());
        double potOdds = calculatePotOdds(game);
        double impliedOdds = calculateImpliedOdds(game);
        
        // Position-based adjustment
        int positionScore = getPositionScore(game, bot);
        
        // Opponent modeling
        double aggression = getOpponentAggression(game);
        
        // Decision tree
        if (handStrength > 0.8) {
            return calculateValueBet(game, handStrength);
        } else if (handStrength > potOdds && impliedOdds > 0) {
            return PlayerAction.CALL;
        } else if (shouldBluff(positionScore, aggression)) {
            return calculateBluffBet(game);
        }
        
        return PlayerAction.FOLD;
    }
    
    private double calculateHandStrength(List<Card> hand, List<Card> community) {
        // Monte Carlo simulation - 1000 random opponent hands
        int wins = 0;
        for (int i = 0; i < 1000; i++) {
            List<Card> opponentHand = generateRandomHand(hand, community);
            if (compareHands(hand, opponentHand, community) > 0) {
                wins++;
            }
        }
        return wins / 1000.0;
    }
}
```

**Előny:**
- Reálisabb játékélmény
- Tanulási lehetőség a játékosnak
- Portfolio showcase: AI/ML

**Becsült idő:** 8-12 óra

---

### 3. Hand History & Replay
**Cél:** Lejátszott kezek visszanézése, elemzése

**Backend:**
```java
@Entity
public class HandHistory {
    @Id
    private UUID id;
    private UUID gameId;
    private int handNumber;
    
    @ElementCollection
    private List<ActionRecord> actions;
    
    @ElementCollection
    private List<Card> board;
    
    private LocalDateTime playedAt;
    private String winnerName;
    private int potSize;
}

@Embeddable
public class ActionRecord {
    private UUID playerId;
    private String playerName;
    private PlayerAction action;
    private int amount;
    private GamePhase phase;
    private LocalDateTime timestamp;
}
```

**Frontend:**
```typescript
// hand-replay.component.ts
export class HandReplayComponent {
  actions: ActionRecord[] = [];
  currentIndex = 0;
  
  play() {
    interval(1000).pipe(
      take(this.actions.length)
    ).subscribe(i => {
      this.applyAction(this.actions[i]);
    });
  }
  
  stepForward() { ... }
  stepBackward() { ... }
}
```

**Becsült idő:** 6-8 óra

---

## 🟡 MEDIUM PRIORITY (Ajánlott)

### 4. Tournament Mode
**Cél:** Multi-table tournament support

**Features:**
- Blind structure (increasing blinds)
- Table balancing
- Prize pool distribution
- Sit & Go / Scheduled tournaments

**Becsült idő:** 15-20 óra

---

### 5. Player Statistics
**Cél:** Részletes játékos statisztikák

**Metrics:**
- VPIP (Voluntarily Put In Pot)
- PFR (Pre-Flop Raise)
- AF (Aggression Factor)
- WTSD (Went to Showdown)
- Win Rate

```java
@Entity
public class PlayerStats {
    private UUID playerId;
    private int handsPlayed;
    private int handsWon;
    private BigDecimal totalWinnings;
    private double vpip;
    private double pfr;
    private double aggressionFactor;
}
```

**Becsült idő:** 8-10 óra

---

### 6. Responsive Mobile UI
**Cél:** Mobile-first design

**Feladatok:**
- Érintőképernyő-barát gombok
- Swipe akciók
- Portrait/Landscape layout
- PWA support

**Becsült idő:** 10-15 óra

---

## 🟢 LOW PRIORITY (Nice to Have)

### 7. Social Features
- Friend list
- Private tables
- Chat system
- Achievements/Badges

### 8. Customization
- Kártya design választás
- Asztal téma
- Avatar upload
- Hang effektek

### 9. Multi-Language Support
- i18n implementáció
- Magyar, English, Német

### 10. Leaderboard
- Napi/Heti/Összes idők
- Skill-based ranking (ELO)

---

## 📊 Prioritási Mátrix

| Feature | Effort | Impact | Priority |
|---------|--------|--------|----------|
| WebSocket | Medium | High | 🔴 High |
| Bot AI | High | High | 🔴 High |
| Hand History | Medium | Medium | 🔴 High |
| Tournament | High | High | 🟡 Medium |
| Statistics | Medium | Medium | 🟡 Medium |
| Mobile UI | Medium | High | 🟡 Medium |
| Social | High | Medium | 🟢 Low |
| Customization | Low | Low | 🟢 Low |
| Multi-Lang | Low | Low | 🟢 Low |
| Leaderboard | Medium | Medium | 🟢 Low |

---

## 🛠️ Technical Debt

### Prioritás szerint:

1. **Test Coverage növelése** (jelenleg ~35%)
   - Cél: 80%+
   - Controller tesztek
   - Service integration tesztek

2. **Error Handling javítása**
   - Global exception handler
   - Structured error responses
   - Frontend error boundaries

3. **Performance optimalizáció**
   - Database query optimization
   - Caching (Redis)
   - Lazy loading

4. **Security hardening**
   - Rate limiting
   - Input sanitization
   - CORS configuration review

---

## 📅 Suggested Roadmap

### Phase 1 (1-2 hét)
- [x] Bug fixes
- [x] Showdown implementation
- [x] Tests
- [ ] WebSocket basics

### Phase 2 (2-3 hét)
- [ ] Advanced Bot AI
- [ ] Hand History
- [ ] Statistics v1

### Phase 3 (3-4 hét)
- [ ] Tournament Mode
- [ ] Mobile UI
- [ ] Performance optimization

### Phase 4 (4+ hét)
- [ ] Social features
- [ ] Customization
- [ ] Production deployment

---

## 💡 Quick Wins

Kis erőfeszítéssel nagy hatás:

1. **Sound effects** - Akció hangok (fold, check, chip sounds)
2. **Animations** - Kártya animációk CSS-el
3. **Keyboard shortcuts** - F=Fold, C=Call, R=Raise
4. **Auto-muck** - Vesztes kéz automatikus eldobása
5. **Time bank** - Extra gondolkodási idő

---

## Contact

Kérdések esetén: adam@porkolab.com
