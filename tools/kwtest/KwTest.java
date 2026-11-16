public class KwTest {
    static String[] yes = {"mj","MJ","Mj","mJ","mjmj","MJMJ","mjMj","mjmjmj","  mj  ","MJmj"};
    static String[] no = {"m","j","mjm","mjmjm","abc mj","mj abc","","jm","mm","jj","蜘蛛侠x"};
    public static void main(String[] a) {
        int fail = 0;
        for (String s : yes) if (!io.github.sjtt2.mjcomeback.MjKeywords.matches(s, "")) { System.out.println("FAIL should match: [" + s + "]"); fail++; }
        for (String s : no) if (io.github.sjtt2.mjcomeback.MjKeywords.matches(s, "")) { System.out.println("FAIL should not match: [" + s + "]"); fail++; }
        if (!io.github.sjtt2.mjcomeback.MjKeywords.matches("蜘蛛侠", "蜘蛛侠,spiderman")) { System.out.println("FAIL extra keyword"); fail++; }
        if (io.github.sjtt2.mjcomeback.MjKeywords.matches("我在看蜘蛛侠", "蜘蛛侠")) { System.out.println("FAIL extra partial"); fail++; }
        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES: " + fail));
    }
}
