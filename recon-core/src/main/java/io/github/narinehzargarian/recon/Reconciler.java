package io.github.narinehzargarian.recon;

import io.github.narinehzargarian.recon.internal.Assignment;
import io.github.narinehzargarian.recon.internal.Similarity;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Matches transactions between two sources in three passes, each working only on what earlier
 * passes left unmatched:
 *
 * <ol>
 *   <li>Shared references, such as invoice numbers.
 *   <li>One-to-one matches within the amount tolerance and date window.
 *   <li>Group matches, where one transaction equals several on the other side, less any fees.
 * </ol>
 *
 * <p>A transaction is used in at most one match. Results are deterministic: the same input always
 * gives the same output, regardless of thread or hash order.
 */
public final class Reconciler {

    /** Most candidates considered for one group search, nearest by date first. */
    static final int GROUP_CANDIDATE_LIMIT = 40;
    /** Most search steps for one group, so a pathological input can't run forever. */
    static final int GROUP_SEARCH_BUDGET = 3_000_000;

    private final MatchConfig config;

    public Reconciler(MatchConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public ReconciliationResult reconcile(List<Transaction> left, List<Transaction> right) {
        requireUniqueIds(left, "left");
        requireUniqueIds(right, "right");
        Run run = new Run(left, right);
        run.matchReferences();
        run.matchOneToOne();
        if (config.groupMatching()) {
            run.matchGroups(Side.RIGHT);
            run.matchGroups(Side.LEFT);
        }
        return run.result();
    }

    private static void requireUniqueIds(List<Transaction> transactions, String side) {
        Set<String> seen = new HashSet<>();
        for (Transaction transaction : transactions) {
            if (!seen.add(transaction.id())) {
                throw new IllegalArgumentException("Duplicate id '" + transaction.id() + "' in " + side + " transactions");
            }
        }
    }

    private enum Side { LEFT, RIGHT }

    private record Edge(int left, int right, double cost, long amountDifference, int dateDifference, double similarity) {
    }

    private final class Run {
        private final List<Transaction> left;
        private final List<Transaction> right;
        private final boolean[] leftUsed;
        private final boolean[] rightUsed;
        private final Similarity.Profile[] leftProfiles;
        private final Similarity.Profile[] rightProfiles;
        private final List<Match> matches = new ArrayList<>();
        private int groupSearchLimitHits;

        Run(List<Transaction> left, List<Transaction> right) {
            this.left = List.copyOf(left);
            this.right = List.copyOf(right);
            this.leftUsed = new boolean[left.size()];
            this.rightUsed = new boolean[right.size()];
            this.leftProfiles = this.left.stream().map(t -> Similarity.Profile.of(t.description())).toArray(Similarity.Profile[]::new);
            this.rightProfiles = this.right.stream().map(t -> Similarity.Profile.of(t.description())).toArray(Similarity.Profile[]::new);
        }

        // ---- Pass 1: shared references ----

        void matchReferences() {
            Map<String, List<Integer>> rightByReference = new HashMap<>();
            for (int j = 0; j < right.size(); j++) {
                String reference = right.get(j).reference();
                if (reference != null) {
                    rightByReference.computeIfAbsent(reference.toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(j);
                }
            }
            for (int i = 0; i < left.size(); i++) {
                Transaction l = left.get(i);
                if (l.reference() == null) {
                    continue;
                }
                List<Integer> candidates = rightByReference.getOrDefault(l.reference().toUpperCase(Locale.ROOT), List.of());
                Integer best = null;
                long bestDays = Long.MAX_VALUE;
                for (int j : candidates) {
                    Transaction r = right.get(j);
                    long days = daysBetween(l, r);
                    // The reference alone isn't trusted: the amount and date must agree too.
                    if (!rightUsed[j] && compatible(l, r) && days < bestDays) {
                        best = j;
                        bestDays = days;
                    }
                }
                if (best != null) {
                    Transaction r = right.get(best);
                    leftUsed[i] = true;
                    rightUsed[best] = true;
                    matches.add(new Match(MatchType.REFERENCE, l.currency(), List.of(l.id()), List.of(r.id()),
                            r.amountMinor() - l.amountMinor(), 0, (int) bestDays, leftProfiles[i].similarity(rightProfiles[best]), false));
                }
            }
        }

        // ---- Pass 2: one-to-one ----

        void matchOneToOne() {
            List<Edge> edges = candidateEdges();
            // Clusters of transactions that compete for each other. Each is solved on its own.
            int[] parent = new int[left.size() + right.size()];
            for (int k = 0; k < parent.length; k++) {
                parent[k] = k;
            }
            for (Edge edge : edges) {
                union(parent, edge.left(), left.size() + edge.right());
            }
            Map<Integer, List<Edge>> clusters = new TreeMap<>();
            for (Edge edge : edges) {
                clusters.computeIfAbsent(find(parent, edge.left()), key -> new ArrayList<>()).add(edge);
            }
            for (List<Edge> cluster : clusters.values()) {
                solveCluster(cluster);
            }
        }

        private List<Edge> candidateEdges() {
            // Right side indexed by currency, then sorted by amount, so each left transaction only
            // looks at the narrow amount range it could match. This keeps large files fast.
            Map<String, List<Integer>> rightByCurrency = new HashMap<>();
            for (int j = 0; j < right.size(); j++) {
                if (!rightUsed[j]) {
                    rightByCurrency.computeIfAbsent(right.get(j).currency(), key -> new ArrayList<>()).add(j);
                }
            }
            Map<String, long[]> amountsByCurrency = new HashMap<>();
            for (Map.Entry<String, List<Integer>> entry : rightByCurrency.entrySet()) {
                entry.getValue().sort(Comparator.comparingLong((Integer j) -> right.get(j).amountMinor()).thenComparing(j -> j));
                amountsByCurrency.put(entry.getKey(), entry.getValue().stream().mapToLong(j -> right.get(j).amountMinor()).toArray());
            }

            List<Edge> edges = new ArrayList<>();
            long tolerance = config.amountToleranceMinor();
            for (int i = 0; i < left.size(); i++) {
                if (leftUsed[i]) {
                    continue;
                }
                Transaction l = left.get(i);
                List<Integer> sorted = rightByCurrency.get(l.currency());
                if (sorted == null) {
                    continue;
                }
                long[] amounts = amountsByCurrency.get(l.currency());
                for (int k = lowerBound(amounts, l.amountMinor() - tolerance); k < amounts.length && amounts[k] <= l.amountMinor() + tolerance; k++) {
                    int j = sorted.get(k);
                    Transaction r = right.get(j);
                    long days = daysBetween(l, r);
                    if (days > config.dateWindowDays()) {
                        continue;
                    }
                    double similarity = leftProfiles[i].similarity(rightProfiles[j]);
                    if (similarity < config.minDescriptionSimilarity()) {
                        continue;
                    }
                    long difference = r.amountMinor() - l.amountMinor();
                    double cost = (double) Math.abs(difference) / (tolerance + 1)
                            + (double) days / (config.dateWindowDays() + 1)
                            + 0.5 * (1 - similarity);
                    edges.add(new Edge(i, j, cost, difference, (int) days, similarity));
                }
            }
            return edges;
        }

        private void solveCluster(List<Edge> cluster) {
            List<Integer> rows = cluster.stream().map(Edge::left).distinct().sorted().toList();
            List<Integer> columns = cluster.stream().map(Edge::right).distinct().sorted().toList();
            List<Edge> chosen = new ArrayList<>();
            if (rows.size() <= config.optimalComponentLimit() && columns.size() <= config.optimalComponentLimit()) {
                Map<Integer, Integer> rowIndex = indexOf(rows);
                Map<Integer, Integer> columnIndex = indexOf(columns);
                double[][] cost = new double[rows.size()][columns.size()];
                Edge[][] byCell = new Edge[rows.size()][columns.size()];
                for (double[] row : cost) {
                    Arrays.fill(row, Assignment.FORBIDDEN);
                }
                for (Edge edge : cluster) {
                    int r = rowIndex.get(edge.left());
                    int c = columnIndex.get(edge.right());
                    cost[r][c] = edge.cost();
                    byCell[r][c] = edge;
                }
                int[] assigned = Assignment.solve(cost);
                for (int r = 0; r < assigned.length; r++) {
                    if (assigned[r] >= 0) {
                        chosen.add(byCell[r][assigned[r]]);
                    }
                }
            } else {
                // Too big to solve exactly in reasonable time: take the cheapest pairs first.
                List<Edge> sorted = new ArrayList<>(cluster);
                sorted.sort(Comparator.comparingDouble(Edge::cost).thenComparingInt(Edge::left).thenComparingInt(Edge::right));
                Set<Integer> takenLeft = new HashSet<>();
                Set<Integer> takenRight = new HashSet<>();
                for (Edge edge : sorted) {
                    if (takenLeft.add(edge.left())) {
                        if (takenRight.add(edge.right())) {
                            chosen.add(edge);
                        } else {
                            takenLeft.remove(edge.left());
                        }
                    }
                }
            }
            chosen.sort(Comparator.comparingInt(Edge::left));
            for (Edge edge : chosen) {
                leftUsed[edge.left()] = true;
                rightUsed[edge.right()] = true;
                boolean exact = edge.amountDifference() == 0 && edge.dateDifference() == 0;
                matches.add(new Match(exact ? MatchType.EXACT : MatchType.FUZZY, left.get(edge.left()).currency(),
                        List.of(left.get(edge.left()).id()), List.of(right.get(edge.right()).id()),
                        edge.amountDifference(), 0, edge.dateDifference(), edge.similarity(), isAmbiguous(edge, cluster)));
            }
        }

        /** Another candidate was exactly as good, so the choice between them was arbitrary. */
        private boolean isAmbiguous(Edge chosen, List<Edge> cluster) {
            for (Edge other : cluster) {
                boolean shares = other.left() == chosen.left() || other.right() == chosen.right();
                if (other != chosen && shares && Math.abs(other.cost() - chosen.cost()) < 1e-9) {
                    return true;
                }
            }
            return false;
        }

        // ---- Pass 3: groups ----

        /** One transaction on {@code single} side against several on the other. */
        void matchGroups(Side single) {
            List<Transaction> singles = single == Side.RIGHT ? right : left;
            List<Transaction> pool = single == Side.RIGHT ? left : right;
            boolean[] singleUsed = single == Side.RIGHT ? rightUsed : leftUsed;
            boolean[] poolUsed = single == Side.RIGHT ? leftUsed : rightUsed;

            Integer[] order = new Integer[singles.size()];
            for (int k = 0; k < order.length; k++) {
                order[k] = k;
            }
            Arrays.sort(order, Comparator.comparing((Integer k) -> singles.get(k).date()).thenComparing(k -> k));

            for (int s : order) {
                if (singleUsed[s] || singles.get(s).amountMinor() == 0) {
                    continue;
                }
                Transaction target = singles.get(s);
                List<Integer> candidates = groupCandidates(target, pool, poolUsed);
                if (candidates.size() < 2) {
                    continue;
                }
                GroupSearch search = new GroupSearch(target, pool, candidates);
                search.run();
                if (search.budgetExceeded) {
                    groupSearchLimitHits++;
                }
                if (search.best == null) {
                    continue;
                }
                singleUsed[s] = true;
                List<String> members = new ArrayList<>();
                long gross = 0;
                long fees = 0;
                long maxDays = 0;
                for (int p : search.best) {
                    poolUsed[p] = true;
                    Transaction member = pool.get(p);
                    members.add(member.id());
                    gross += member.amountMinor();
                    fees += Long.signum(member.amountMinor()) * config.feeFor(member.amountMinor());
                    maxDays = Math.max(maxDays, ChronoUnit.DAYS.between(member.date(), target.date()) < 0
                            ? -ChronoUnit.DAYS.between(member.date(), target.date()) : ChronoUnit.DAYS.between(member.date(), target.date()));
                }
                long difference = target.amountMinor() - (gross - fees);
                List<String> leftIds = single == Side.RIGHT ? members : List.of(target.id());
                List<String> rightIds = single == Side.RIGHT ? List.of(target.id()) : members;
                long expectedSide = single == Side.RIGHT ? difference : -difference;
                matches.add(new Match(MatchType.GROUP, target.currency(), leftIds, rightIds, expectedSide, Math.abs(fees), (int) maxDays, 0, search.solutions > 1));
            }
        }

        private List<Integer> groupCandidates(Transaction target, List<Transaction> pool, boolean[] poolUsed) {
            List<Integer> candidates = new ArrayList<>();
            for (int p = 0; p < pool.size(); p++) {
                Transaction member = pool.get(p);
                long daysBefore = ChronoUnit.DAYS.between(member.date(), target.date());
                // Members fall before the single transaction (a payout follows its charges), with
                // the one-to-one date window as slack the other way.
                boolean inWindow = daysBefore <= config.groupWindowDays() && daysBefore >= -config.dateWindowDays();
                boolean sameSign = Long.signum(member.amountMinor()) == Long.signum(target.amountMinor());
                if (!poolUsed[p] && inWindow && sameSign && member.currency().equals(target.currency())) {
                    candidates.add(p);
                }
            }
            // Members on or before the single transaction come first, nearest first; members after
            // it only fit if dates were recorded loosely, so they come last.
            candidates.sort(Comparator.comparingLong((Integer p) -> {
                long daysBefore = ChronoUnit.DAYS.between(pool.get(p).date(), target.date());
                return daysBefore >= 0 ? daysBefore : config.groupWindowDays() + 1 - daysBefore;
            }).thenComparing(p -> p));
            return candidates.size() > GROUP_CANDIDATE_LIMIT ? candidates.subList(0, GROUP_CANDIDATE_LIMIT) : candidates;
        }

        /** Searches subsets of 2 to maxGroupSize candidates whose net amount equals the target. */
        private final class GroupSearch {
            private final long target;
            private final long[] nets;
            private final long[] daysAway;
            private final long[] suffixTotal;
            private final int[] poolIndex;
            private final int[] path;
            private int steps;
            boolean budgetExceeded;
            int solutions;
            int[] best;
            private long bestDifference = Long.MAX_VALUE;
            private long bestSpread = Long.MAX_VALUE;

            GroupSearch(Transaction single, List<Transaction> pool, List<Integer> candidates) {
                this.target = Math.abs(single.amountMinor());
                int n = candidates.size();
                Integer[] byNet = candidates.toArray(new Integer[0]);
                long[] netOf = new long[pool.size()];
                for (int p : candidates) {
                    long gross = Math.abs(pool.get(p).amountMinor());
                    netOf[p] = gross - config.feeFor(gross);
                }
                // Largest first, so impossible branches are cut early.
                Arrays.sort(byNet, Comparator.comparingLong((Integer p) -> -netOf[p]).thenComparing(p -> p));
                this.poolIndex = new int[n];
                this.nets = new long[n];
                this.daysAway = new long[n];
                for (int k = 0; k < n; k++) {
                    poolIndex[k] = byNet[k];
                    nets[k] = netOf[byNet[k]];
                    daysAway[k] = Math.abs(ChronoUnit.DAYS.between(pool.get(byNet[k]).date(), single.date()));
                }
                this.suffixTotal = new long[n + 1];
                for (int k = n - 1; k >= 0; k--) {
                    suffixTotal[k] = suffixTotal[k + 1] + nets[k];
                }
                this.path = new int[config.maxGroupSize()];
            }

            void run() {
                search(0, 0, 0);
            }

            private void search(int start, int depth, long total) {
                if (++steps > GROUP_SEARCH_BUDGET) {
                    budgetExceeded = true;
                    return;
                }
                long tolerance = config.groupToleranceMinor();
                if (depth >= 2 && Math.abs(total - target) <= tolerance) {
                    record(depth, Math.abs(total - target));
                }
                if (depth == config.maxGroupSize()) {
                    return;
                }
                for (int k = start; k < nets.length && !budgetExceeded; k++) {
                    long next = total + nets[k];
                    if (next > target + tolerance) {
                        continue;  // Too big; a smaller later candidate may still fit.
                    }
                    if (total + suffixTotal[k] < target - tolerance) {
                        return;  // Even taking everything left can't reach the target.
                    }
                    path[depth] = k;
                    search(k + 1, depth + 1, next);
                }
            }

            private void record(int size, long difference) {
                solutions++;
                long spread = 0;
                for (int d = 0; d < size; d++) {
                    spread = Math.max(spread, daysAway[path[d]]);
                }
                // Prefer the closest amount: with a tolerance, many wrong combinations come close,
                // but usually only the true one is exact. Then the fewest transactions, then the
                // ones nearest in date.
                boolean better = best == null
                        || difference < bestDifference
                        || (difference == bestDifference && size < best.length)
                        || (difference == bestDifference && size == best.length && spread < bestSpread);
                if (better) {
                    best = new int[size];
                    for (int d = 0; d < size; d++) {
                        best[d] = poolIndex[path[d]];
                    }
                    Arrays.sort(best);
                    bestDifference = difference;
                    bestSpread = spread;
                }
            }
        }

        // ---- Result ----

        ReconciliationResult result() {
            List<Transaction> unmatchedLeft = new ArrayList<>();
            List<Transaction> unmatchedRight = new ArrayList<>();
            long unmatchedLeftTotal = 0;
            long unmatchedRightTotal = 0;
            for (int i = 0; i < left.size(); i++) {
                if (!leftUsed[i]) {
                    unmatchedLeft.add(left.get(i));
                    unmatchedLeftTotal += left.get(i).amountMinor();
                }
            }
            for (int j = 0; j < right.size(); j++) {
                if (!rightUsed[j]) {
                    unmatchedRight.add(right.get(j));
                    unmatchedRightTotal += right.get(j).amountMinor();
                }
            }
            Map<MatchType, Integer> byType = new EnumMap<>(MatchType.class);
            int ambiguous = 0;
            int matchedLeft = 0;
            int matchedRight = 0;
            for (Match match : matches) {
                byType.merge(match.type(), 1, Integer::sum);
                ambiguous += match.ambiguous() ? 1 : 0;
                matchedLeft += match.leftIds().size();
                matchedRight += match.rightIds().size();
            }
            var summary = new ReconciliationResult.Summary(left.size(), right.size(), matchedLeft, matchedRight,
                    unmatchedLeftTotal, unmatchedRightTotal, byType, ambiguous, groupSearchLimitHits);
            return new ReconciliationResult(matches, unmatchedLeft, unmatchedRight,
                    possibleDuplicates(left), possibleDuplicates(right), summary);
        }

        private List<List<String>> possibleDuplicates(List<Transaction> transactions) {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (Transaction t : transactions) {
                String key = t.date() + "|" + t.amountMinor() + "|" + t.currency() + "|" + Similarity.normalize(t.description());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t.id());
            }
            return groups.values().stream().filter(ids -> ids.size() > 1).map(List::copyOf).toList();
        }

        private boolean compatible(Transaction l, Transaction r) {
            return l.currency().equals(r.currency())
                    && Math.abs(r.amountMinor() - l.amountMinor()) <= config.amountToleranceMinor()
                    && daysBetween(l, r) <= config.dateWindowDays();
        }
    }

    private static long daysBetween(Transaction a, Transaction b) {
        return Math.abs(ChronoUnit.DAYS.between(a.date(), b.date()));
    }

    private static int lowerBound(long[] sorted, long value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int find(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static Map<Integer, Integer> indexOf(List<Integer> values) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int k = 0; k < values.size(); k++) {
            result.put(values.get(k), k);
        }
        return result;
    }
}
