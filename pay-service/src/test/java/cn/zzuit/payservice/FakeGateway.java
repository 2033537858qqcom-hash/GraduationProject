package cn.zzuit.payservice;

import java.util.Map;

class FakeGateway implements AlipayGateway {
    int precreates;
    Query next;
    boolean valid = true;
    public String precreate(String o, String a, String s, int t) { precreates++; return "https://qr.alipay.com/fake" + o; }
    public Query query(String o) { return next; }
    public void close(String o) {}
    public boolean verify(Map<String, String> p) { return valid && "ok".equals(p.get("sign")); }
    public String appId() { return "app1"; }
}
