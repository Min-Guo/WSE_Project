package Retriever;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Created by Wenzhao on 4/22/16.
 */
public class Page {
    private String id;
    private double pageRank;
    private String content = null;
    private String lowerContent = null;
    private String url;
    private String title;
    private double dependencyScore;
    private double totalScore;
    private int length;
    private String preview = " ";
    private int previewTokenSize = 0;
    private String scoreInfo = "";
    private boolean exist = false;
    private String pagePath;
    private int match = -1;

    public Page(String id, double pageRank, String pagePath) {
        this.id = id;
        this.pageRank = pageRank;
        this.pagePath = pagePath;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return title + "\n" + url + "\n" + preview + "\n";
    }

    public String getID() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public double getPageRank() {
        return pageRank;
    }

    public int getMatch() {
        return match;
    }

    public String getScoreInfo() {
        return scoreInfo + "Dependency Score=" + dependencyScore + " pageRank=" + pageRank +
                " total=" + totalScore + "\n";
    }

    public void calculateScore(String token, double wordWeight) {
        int lowerCount = getCount(token.toLowerCase(), lowerContent);
        if (lowerCount == 0) {
            scoreInfo += "Token: " + token + " Original=0 Lower=0 WordWeight=" + wordWeight + " wordTotal=0\n";
            return;
        }
        int originalCount = getCount(token, content);
        lowerCount = lowerCount - originalCount;
        if (lowerCount != 0 || originalCount != 0) {
            String[] parts = token.split("\\s+");
            int size = parts.length;
            setMatch(size);
        }
        double originalScore = formula(wordWeight, originalCount);
        double lowerScore = formula(wordWeight, lowerCount);
        final double weight = 1.5;
        double addedScore = weight * originalScore + lowerScore;
        scoreInfo += "Token: " + token + " Original=" + originalScore + " Lower=" + lowerScore +
                 " WordWeight=" + wordWeight + " wordTotal=" + addedScore + "\n";
        dependencyScore += addedScore;
    }

    public double finalScore() {
        final double weight = 0;
        totalScore = dependencyScore + weight * pageRank;
        return totalScore;
    }

    public boolean isValid() {
        return exist;
    }

    public void parsePage() {
        int first = id.indexOf('_', 0);
        int second = id.indexOf('_', first + 1);
        String firstDir = "result_" + id.substring(0, first);
        String secondDir = id.substring(0, second);
        String fileName = id + ".page";
        if (!pagePath.endsWith(File.separator)) {
            pagePath += File.separator;
        }
        String wholePath = pagePath + firstDir + File.separator + secondDir
                + File.separator + fileName;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(wholePath));
            String line = null;
            while((line = reader.readLine()) != null) {
                if (line.equals("#ThisURL#")) {
                    url = reader.readLine();
                }
                else if (line.equals("#Length#")) {
                    length = Integer.parseInt(reader.readLine());
                }
                else if (line.equals("#Title#")) {
                    title = reader.readLine();
                }
                else if (line.equals("#Content#")) {
                    content = reader.readLine();
                    lowerContent = content.toLowerCase();
                    break;
                }
            }
            reader.close();
            if (content == null) {
                return;
            }
            exist = true;
        } catch (IOException e) {
            System.out.println("Parse page " + id + " not successful");
        }
    }

    private int getCount(String token, String content) {
        int index = 0;
        int count = 0;
        String[] parts = token.split("\\s+");
        int size = parts.length;
        boolean hasPreview = false;
        while ((index = content.indexOf(token, index)) != -1) {
            if (size >= previewTokenSize && !hasPreview) {
                int end = Math.min(content.length(), index + 200);
                while (end < content.length() && content.charAt(end) != ' ') {
                    end++;
                }
                preview = content.substring(index, end);
                hasPreview = true;
                previewTokenSize = size;
            }
            count++;
            index += token.length();
        }
        return count;
    }

    private void setMatch(int match) {
        this.match = match;
    }

    private double formula(double wordWeight, int count) {
        if (count == 0) {
            return 0;
        }
        double score = 1 + Math.log(count) / Math.log(2);
        return score * wordWeight;
    }
}