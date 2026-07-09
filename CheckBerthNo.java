import java.net.*;
import java.io.*;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.text.SimpleDateFormat;

public class CheckBerthNo {
    public static void main(String[] args) throws Exception {
        // SSL 전체 신뢰 설정
        TrustManager[] trustAll = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

        // 세션 획득
        URL url = new URL("https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus");
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        String cookie = "";
        String setCookie = con.getHeaderField("Set-Cookie");
        if (setCookie != null) cookie = setCookie.split(";")[0];

        StringBuilder html = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) html.append(line);
        }

        String token = "", header = "";
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);
        if (m.find()) token = m.group(1);
        m = java.util.regex.Pattern.compile("name=\"_csrf_header\" content=\"([^\"]+)\"").matcher(html);
        if (m.find()) header = m.group(1);

        // 스케줄 조회 D-1 ~ D+8
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
        cal.add(Calendar.DAY_OF_YEAR, -1);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        String from = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, 9);
        String to = sdf.format(cal.getTime());

        URL schUrl = new URL("https://info.dgtbusan.com/DGT/berth/vesselSchedule");
        HttpsURLConnection schCon = (HttpsURLConnection) schUrl.openConnection();
        schCon.setRequestMethod("POST");
        schCon.setRequestProperty("User-Agent", "Mozilla/5.0");
        schCon.setRequestProperty("Cookie", cookie);
        if (!header.isEmpty()) schCon.setRequestProperty(header, token);
        schCon.setRequestProperty("Content-Type", "application/json");
        schCon.setDoOutput(true);
        String body = "{\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\"}";
        schCon.getOutputStream().write(body.getBytes("UTF-8"));

        StringBuilder res = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(schCon.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) res.append(line);
        }

        // berthNo 값 추출
        Set<String> berthNos = new TreeSet<>();
        m = java.util.regex.Pattern.compile("\"berthNo\":\"([^\"]+)\"").matcher(res);
        while (m.find()) berthNos.add(m.group(1));

        System.out.println("=== DGT API 실제 berthNo 값 목록 ===");
        for (String b : berthNos) System.out.println("  berthNo: [" + b + "]");

        // 전체 응답에서 F 포함 항목 출력
        System.out.println("\n=== F 선석 관련 전체 항목 ===");
        m = java.util.regex.Pattern.compile("\\{[^}]*\"berthNo\":\"(F[^\"]*)\",?[^}]*\\}").matcher(res);
        int cnt = 0;
        while (m.find() && cnt < 5) {
            System.out.println("  " + m.group(0).substring(0, Math.min(200, m.group(0).length())));
            cnt++;
        }
    }
}
