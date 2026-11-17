package cn.zzuit.campus.agent;

import java.util.*;

/** Shortest-path walking route graph over verified East & West campus road intersections and buildings. */
public final class AgentNavGraph {
    public record RoutePlan(List<String> waypoints, int estimatedMeters, int estimatedMinutes) {}

    private record Edge(String to, int meters) {}

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, List<Edge>> GRAPH = new HashMap<>();

    static {
        // East Campus (学院路16号)
        node("south-gate", "南门");
        node("north-gate", "北门");
        node("east-gate", "东门");
        node("east-square", "英才广场");
        node("east-library", "图书馆");
        node("east-admin", "行政楼");
        node("east-auditorium", "学术报告厅");
        node("east-xinke", "信科楼组团");
        node("east-jiangong", "建工楼");
        node("east-construction-training", "建工实训中心");
        node("east-construction", "建工学院");
        node("east-wenhua", "文华楼组团");
        node("east-art", "美术、音乐楼组团");
        node("east-medical", "医学院、药学院组团");
        node("east-clinic", "医务室");
        node("east-affairs", "学生综合事务服务中心");
        node("east-water", "东侧水系（秋月塘）");
        node("east-canteen-wenhua", "文华餐厅");
        node("east-jinxiu-dining", "锦绣餐厅");
        node("east-jinxiu", "毓秀楼");
        node("east-fenghua", "风华楼");
        node("east-shuhua", "书华楼");
        node("east-zhiyuan", "致远楼");
        node("east-jingyi", "静逸楼");
        node("east-h8", "H8公寓");
        node("east-h1", "H1公寓");
        node("east-h2", "H2公寓");
        node("east-gym", "红体馆");
        node("east-swimming", "游泳馆");
        node("east-sports", "西侧体育场");

        edge("south-gate", "east-admin", 160);
        edge("south-gate", "east-construction", 180);
        edge("south-gate", "east-medical", 240);
        edge("east-construction", "east-construction-training", 110);
        edge("east-construction-training", "east-canteen-wenhua", 120);
        edge("east-canteen-wenhua", "east-admin", 110);
        edge("east-canteen-wenhua", "east-h2", 90);
        edge("east-h2", "east-h1", 80);
        edge("east-h1", "east-sports", 110);
        edge("east-sports", "east-gym", 140);
        edge("east-gym", "east-swimming", 80);
        edge("east-swimming", "east-art", 95);
        edge("east-art", "east-square", 100);
        edge("east-square", "east-library", 90);
        edge("east-admin", "east-auditorium", 110);
        edge("east-admin", "east-jiangong", 120);
        edge("east-jiangong", "east-xinke", 95);
        edge("east-xinke", "east-library", 110);
        edge("east-xinke", "east-square", 115);
        edge("east-library", "east-wenhua", 110);
        edge("east-wenhua", "east-water", 130);
        edge("east-auditorium", "east-water", 110);
        edge("east-water", "east-affairs", 140);
        edge("east-affairs", "east-gate", 150);
        edge("east-medical", "east-clinic", 130);
        edge("east-clinic", "east-affairs", 140);
        edge("east-wenhua", "east-jinxiu", 130);
        edge("east-jinxiu", "east-fenghua", 85);
        edge("east-fenghua", "east-water", 100);
        edge("east-library", "east-jingyi", 170);
        edge("east-jingyi", "east-zhiyuan", 85);
        edge("east-jingyi", "east-jinxiu-dining", 90);
        edge("east-jinxiu-dining", "east-shuhua", 110);
        edge("east-shuhua", "east-zhiyuan", 85);
        edge("east-shuhua", "east-h8", 95);
        edge("east-h8", "north-gate", 110);
        edge("east-zhiyuan", "north-gate", 120);

        // West Campus (桃园路 / 育才路 / 万庄路)
        node("west-flag-square", "国旗广场");
        node("west-mingde", "西校区明德楼");
        node("west-library", "西校区图书馆");
        node("west-chengyi", "诚毅楼");
        node("west-information", "信息楼");
        node("west-tech-center", "工程训练与创新中心");
        node("west-experiment-building", "西校区实验楼");
        node("west-lab-3", "叁号实验楼");
        node("west-teaching-1", "1号楼（东院）");
        node("west-teaching-2", "2号楼");
        node("west-incubator", "大学生创业孵化园");
        node("west-auto-training", "汽车实训中心");
        node("west-rail-training", "轨道交通实训中心");
        node("west-canteen", "郑工餐厅");
        node("west-steel-canteen", "钢结构餐厅");
        node("west-siyuan-canteen", "思源餐厅");
        node("west-dorm-4", "4号公寓楼");
        node("west-dorm-5", "5号宿舍楼");
        node("west-dorm-7", "7号宿舍楼");
        node("west-sports-ground", "西校区体育场");

        edge("west-flag-square", "west-mingde", 90);
        edge("west-mingde", "west-library", 110);
        edge("west-mingde", "west-information", 115);
        edge("west-library", "west-chengyi", 105);
        edge("west-library", "west-teaching-2", 120);
        edge("west-information", "west-experiment-building", 100);
        edge("west-chengyi", "west-experiment-building", 95);
        edge("west-experiment-building", "west-tech-center", 130);
        edge("west-tech-center", "west-auto-training", 110);
        edge("west-auto-training", "west-rail-training", 95);
        edge("west-teaching-2", "west-teaching-1", 100);
        edge("west-teaching-2", "west-incubator", 150);
        edge("west-teaching-1", "west-lab-3", 125);
        edge("west-lab-3", "west-canteen", 90);
        edge("west-canteen", "west-steel-canteen", 110);
        edge("west-steel-canteen", "west-sports-ground", 120);
        edge("west-sports-ground", "west-siyuan-canteen", 115);
        edge("west-siyuan-canteen", "west-dorm-4", 95);
        edge("west-dorm-4", "west-dorm-5", 105);
        edge("west-dorm-5", "west-dorm-7", 110);
        edge("west-dorm-7", "west-lab-3", 135);
    }

    private AgentNavGraph() {}

    private static void node(String id, String label) {
        LABELS.put(id, label);
        GRAPH.putIfAbsent(id, new ArrayList<>());
    }

    private static void edge(String a, String b, int meters) {
        GRAPH.computeIfAbsent(a, k -> new ArrayList<>()).add(new Edge(b, meters));
        GRAPH.computeIfAbsent(b, k -> new ArrayList<>()).add(new Edge(a, meters));
    }

    public static Optional<RoutePlan> shortestRoute(String startId, String endId) {
        if (startId == null || endId == null || !GRAPH.containsKey(startId) || !GRAPH.containsKey(endId)) {
            return Optional.empty();
        }
        if (startId.equals(endId)) {
            return Optional.of(new RoutePlan(List.of(LABELS.get(startId)), 0, 0));
        }
        var dist = new HashMap<String, Integer>();
        var prev = new HashMap<String, String>();
        var queue = new PriorityQueue<String>(Comparator.comparingInt(dist::get));
        dist.put(startId, 0);
        queue.add(startId);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(endId)) break;
            int d = dist.getOrDefault(cur, Integer.MAX_VALUE);
            for (var e : GRAPH.getOrDefault(cur, List.of())) {
                int nd = d + e.meters();
                if (nd < dist.getOrDefault(e.to(), Integer.MAX_VALUE)) {
                    dist.put(e.to(), nd);
                    prev.put(e.to(), cur);
                    queue.remove(e.to());
                    queue.add(e.to());
                }
            }
        }
        Integer totalMeters = dist.get(endId);
        if (totalMeters == null) return Optional.empty();

        var path = new ArrayList<String>();
        for (String at = endId; at != null; at = prev.get(at)) {
            path.add(LABELS.getOrDefault(at, at));
        }
        Collections.reverse(path);
        int minutes = Math.max(1, (int) Math.round(totalMeters / 75.0));
        return Optional.of(new RoutePlan(List.copyOf(path), totalMeters, minutes));
    }
}
