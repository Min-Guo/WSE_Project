package WebCrawler;

import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * USAGE: java Crawler [-path savePath] [-max searchLimit] [-id jobID]
 * Please pay attention:
 * This program assumes that under the directory variable 'savePath' the user provides,
 * the following two sub-directories have been created: (please use the same capitalization)
 * a directory called 'hashSets', containing the external hashSets from last round, or empty if it's the first round
 * a directory called 'roots', containing url root files named as 'root_1', 'root_2'... the number of such files
 * should be the same with the number of rounds the program to be run, so if we plan to run the program
 * 200 times, then the files 'root_1' - 'root_200' should all exist in this directory
 * For example, to compile and run: (please cd to src)
 * javac WebCrawler/Crawler.java
 * java WebCrawler/Crawler -path ../results/ -max 2500 -id 1
 */

public class Crawler {
    private static UrlQueue urlQueue =
            new UrlQueue();
    private static HashMap<Integer, HashSet<URL>> internalHashMap =
            new HashMap<Integer, HashSet<URL>>();
    private static int searchLimit = 0;
    private static int pageCount = 0;
    private final static int THREAD_COUNT = 15000;
    private static final int EXTERNAL_HASHSET_COUNT = 1000;
    private static final Object[] INTERNAL_HASHSET_LOCK = new Object[EXTERNAL_HASHSET_COUNT];
    private static int jobID;
    private static final String USAGE = "USAGE: java Crawler [-path savePath] [-max searchLimit] [-id jobID]";
    private static FileWriter logWriter;

    /**
     * This method takes the input file Scanner and
     * the path to save the results
     */
    private static void run(Scanner readFile, String savePath) {
        output("Crawling round " + jobID + " has started");
        initialize(readFile);
        crawl(savePath);
    }

    /**
     * This method adds the root urls into the internal hashmap, later when crawling first begins,
     * the root urls will be added to the queue and the external hashset.
     * The input file should have each url in a new line
     */
    private static void initialize(Scanner readFile) {
        for (int i = 0; i < EXTERNAL_HASHSET_COUNT; i++) {
            INTERNAL_HASHSET_LOCK[i] = new Object[i];
        }
        while (readFile.hasNextLine()) {
            try {
                URL url = new URL(readFile.nextLine());
                addToInternalHashMap(url);
            } catch (MalformedURLException e) {
                //ignore invalid urls
            }
        }
    }

    /**
     * This method adds the newly extracted urls (or root urls) to the internal hashmap,
     * if the url is duplicated, just ignore. It also indexes the urls by calculating
     * its hashCode
     */
    private static void addToInternalHashMap(URL url) {
        int hashValue = hash(url);
        HashSet<URL> hashSet = null;
        synchronized (INTERNAL_HASHSET_LOCK[hashValue]) {
            if (internalHashMap.containsKey(hashValue)) {
                hashSet = internalHashMap.get(hashValue);
            }
            else {
                hashSet = new HashSet<URL>();
                internalHashMap.put(hashValue, hashSet);
            }
            hashSet.add(url);
        }
    }

    /**
     * This method calculates the hashCode of a url
     */
    private static int hash(URL url) {
        return Math.abs(url.toString().hashCode()) % EXTERNAL_HASHSET_COUNT;
    }

    /**
     * This method is the crawling process
     */
    private static void crawl(String savePath) {
        setProxy();
        // assume the hashSets directory has been created
        String dirPath = savePath + "hashSets" + File.separator;
        // based on the new addToUrlQueue() design, no real need to call addToUrlQueue() here
        Crawling[] crawlings = new Crawling[THREAD_COUNT];
        Thread[] threads = new Thread[THREAD_COUNT];
        // create the parent directory for this round of crawling
        String resultPath = savePath + "result_" + jobID + File.separator;
        File resultDir = new File(resultPath);
        if (!resultDir.exists()) {
            resultDir.mkdir();
        }
        for (int i = 0; i < THREAD_COUNT; i++) {
            // convert savePath to resultPath in run() method of the thread, instead of here
            crawlings[i] = new Crawling(i, savePath);
            threads[i] = new Thread(crawlings[i]);
            // run the thread after creation
            threads[i].start();
        }
        while (pageCount < searchLimit) {
            try {
                Thread.sleep(2 * 60 * 1000);
            } catch (InterruptedException e) {
                // ignore
            }
            int count = 0;
            for (int i = 0; i < THREAD_COUNT; i++) {
                count += crawlings[i].getDownloadCount();
            }
            pageCount = count;
            output("Total count is " + pageCount);
        }
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // ignore
        }
        output("Crawling round " + jobID + " has ended");
        System.exit(0);
    }

    /**
     * This method sets proxy and ports here, behind a firewall
     */
    private static void setProxy() {
        Properties props= new Properties(System.getProperties());
        props.put("http.proxySet", "true");
        props.put("http.proxyHost", "webcache-cup");
        props.put("http.proxyPort", "8080");
        Properties newprops = new Properties(props);
        System.setProperties(newprops);
    }

    /**
     * This method compares the internal hashmap and external hashset, add non-duplicate
     * urls to both the queue and external hashset, and ignore duplicates
     */
    @SuppressWarnings("unchecked")
    private static void addToUrlQueue(String savePath) {
        // only randomly pick one index, instead of iterating over the whole hashmap,
        // so after this, urlQueue.isEmpty() may still be true, but more threads will come
        int index = (int)(Math.random() * EXTERNAL_HASHSET_COUNT);
        synchronized (INTERNAL_HASHSET_LOCK[index]) {
            if (!urlQueue.isEmpty()) {
                return;
            }
            if (!internalHashMap.containsKey(index)) {
                return;
            }
            String dirPath = savePath + "hashSets" + File.separator;
            // index corresponds to the id of the external hashset
            String externalName = "External" + index + ".ser";
            File file = new File(dirPath + externalName);
            HashSet<URL> internalHashSet = internalHashMap.get(index);
            HashSet<URL> externalHashSet = null;
            if (file.exists()) {
                // load external hashset
                try {
                    FileInputStream fis = new FileInputStream(dirPath + externalName);
                    ObjectInputStream ois = new ObjectInputStream(fis);
                    externalHashSet = (HashSet<URL>) ois.readObject();
                    ois.close();
                    //output("Load external hashset " + index + " successfully");
                } catch (IOException e) {
                    output("Load external hashset " + index + " not successfully");
                } catch (ClassNotFoundException e) {
                    output("Load external hashset " + index + " not successfully");
                }
            }
            // if the external hashset does not exist, create one
            // but if it exists but fails to load, should overwrite it?
            if (externalHashSet == null) {
                externalHashSet = new HashSet<URL>();
            }
            // iterate through the internal hashset, if the url is duplicated, just ignore,
            // if the url is new, add it to both the queue and external hashset
            for (URL url: internalHashSet) {
                if (!externalHashSet.contains(url)) {
                    externalHashSet.add(url);
                    urlQueue.add(url);
                }
            }
            // save external hashset back
            try {
                FileOutputStream fos = new FileOutputStream(dirPath + externalName);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(externalHashSet);
                oos.close();
                //output("Save external hashset " + index + " successfully");
            } catch (IOException e) {
                output("Save external hashset " + index + " not successfully");
            }
            // clear the current hashset, not the entire hashmap
            internalHashSet.clear();
        }
    }

    /**
     * The run() method in this class specifies what each thread is doing
     */
    private static class Crawling implements Runnable {
        private int threadID;
        private String savePath;
        private int downloadCount;

        public Crawling(int id, String path) {
            threadID = id;
            savePath = path;
            downloadCount = 0;
        }

        public void run() {
            // make a separate directory for each thread
            String resultPath = savePath + "result_" + jobID + File.separator;
            String dirPath = resultPath + "Thread" + threadID + "_result" + File.separator;
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdir();
            }
            URL url = null;
            while (pageCount < searchLimit) {
                while (pageCount < searchLimit && (url = urlQueue.poll()) != null) {
                    if (!isRobotSafe(url)) {
                        continue;
                    }
                    String page = getPage(url);
                    // page equals empty indicates the page was not processed successfully
                    // because of various reasons detailed in getPage() method
                    if (page.equals("")) {
                        continue;
                    }
                    // use count as the file name, and only when the page is saved successfully,
                    // the count increments
                    String fileName = threadID + "_" + (downloadCount + 1);
                    try {
                        savePage(page, dirPath, fileName);
                    } catch (IOException e) {
                        continue;
                    }
                    downloadCount++;
                    output("thread " + threadID + " downloaded page " + fileName);
                    indexPage(page);
                    List<URL> newUrls = extractUrl(url, page);
                    for (URL newUrl: newUrls) {
                        // if newUrl is duplicated in the internal hashmap, it will be ignored
                        // by addToInternalHashMap(newUrl), and when the next round of crawling first
                        // begins, the urls in internal hashmap will also be checked against external hashset
                        addToInternalHashMap(newUrl);
                    }
                }
                if (pageCount >= searchLimit) {
                    // output("thread " + threadID + " terminated because search limit is reached");
                }
                // output("thread " + threadID + " paused because queue is empty");
                addToUrlQueue(savePath);
            }
        }

        public int getDownloadCount() {
            return downloadCount;
        }
    }

    /**
     * This method returns whether the page is robot safe
     */
    private static boolean isRobotSafe(URL url) {
        String strHost = url.getHost();
        if (strHost.length() == 0) {
            return false;
        }
        // form URL of the robots.txt file
        String strRobot = "http://" + strHost + "/robots.txt";
        URL urlRobot;
        try {
            urlRobot = new URL(strRobot);
        } catch (MalformedURLException e) {
            // something weird is happening, so don't trust it
            return false;
        }
        InputStream urlRobotStream = null;
        try {
            URLConnection urlConnection = urlRobot.openConnection();
            urlConnection.setConnectTimeout(5000);
            urlConnection.setReadTimeout(5000);
            urlRobotStream = urlConnection.getInputStream();
        } catch (IOException e) {
            return false;
        }
        String strCommands = "";
        try {
            // read in entire file
            byte b[] = new byte[1000];
            int numRead = urlRobotStream.read(b);
            while (numRead != -1) {
                String newCommands = new String(b, 0, numRead);
                strCommands += newCommands;
                numRead = urlRobotStream.read(b);
            }
            urlRobotStream.close();
        } catch (IOException e) {
            // if there is no robots.txt file, it is OK to search
            return true;
        }
        // assume that this robots.txt refers to us and
        // search for "Disallow:" commands.
        String strURL = url.getFile();
        int index = 0;
        final String DISALLOW = "Disallow:";
        while ((index = strCommands.indexOf(DISALLOW, index)) != -1) {
            index += DISALLOW.length();
            String strPath = strCommands.substring(index);
            StringTokenizer st = new StringTokenizer(strPath);
            if (!st.hasMoreTokens()) {
                break;
            }
            String strBadPath = st.nextToken();
            // if the URL starts with a disallowed path, it is not safe
            if (strURL.indexOf(strBadPath) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * This method downloads the page into a string
     * while downloading the page, it should:
     * (1) determine the type of the page, if it's of non-textual type, no need to continue
     * (2) determine if the page is in English, it not no need to continue
     * (3) filter out images etc.
     * (4) ...
     */
    private static String getPage(URL url) {
        long startTime = System.currentTimeMillis();
        try {
            // try opening the URL
            URLConnection urlConnection = url.openConnection();
            urlConnection.setConnectTimeout(5000);
            urlConnection.setReadTimeout(5000);
            urlConnection.setAllowUserInteraction(false);
            HttpURLConnection http = (HttpURLConnection)urlConnection;
            String type = null;
            if (http != null) {
                type = http.getContentType();
            }
            // reference: https://www.w3.org/Protocols/rfc1341/4_Content-Type.html
            // only get text type now, may add more allowed types later
            // how to handle type == null? Allow them now because pages with type == null
            // seems all to be textual type actually
            if (type != null && !type.toLowerCase().startsWith("text")) {
                return "";
            }
            InputStream urlStream = urlConnection.getInputStream();
            // search the input stream for links
            // first, read in the entire URL
            final int BYTE_COUNT = 1000;
            byte b[] = new byte[BYTE_COUNT];
            int numRead = urlStream.read(b);
            String content = "";
            boolean notKnownIfEnglish = true;
            while ((numRead != -1) ) {
                String newContent = new String(b, 0, numRead);
                // check if the page is in English right now,
                // and stop downloading immediately if not
                // notknownIfEnglish == false indicates already knowing it's in English
                // only check newContent for efficiency
                if (notKnownIfEnglish) {
                    int index = newContent.indexOf("lang=\"");
                    // if the tag exists
                    if (index != -1) {
                        index += 6;
                        if (index + 2 <= newContent.length() && newContent.substring(index, index + 2).equals("en")) {
                            notKnownIfEnglish = false;
                        }
                        // lang==some other language, return
                        else if (index + 2 <= newContent.length()) {
                            return "";
                        }
                        // if index + 2 > newContent.length(), it means the tag happens to be in the middle,
                        // this may have some mistakes, ignore them? is there a better way?
                    }
                }
                content += newContent;
                numRead = urlStream.read(b);
                // if lang tag does not exist, allow them. It seems that counting foreign characters
                // does not work well
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * This method should save page to local directory
     */
    private static void savePage(String page, String savePath, String fileName)
            throws IOException {
        fileName += ".html";
        File file = new File(savePath + fileName);
        file.createNewFile();
        FileWriter writer = new FileWriter(file);
        writer.write(page);
        writer.close();
    }

    /**
     * This method should connect to indexing and pageRank calculation
     */
    private static void indexPage(String page) {

    }

    /**
     * This method should parse the page and extract the urls it contains,
     * and return them in an arraylist
     */
    private static List<URL> extractUrl(URL url, String page) {
        List<URL> results = new ArrayList<URL>();
        // remove page.toLowerCase()
        // position in page
        int index = 0;
        int newIndex = 0;
        int iEndAngle, ihref, iURL, iCloseQuote, iHatchMark, iEnd;
        while ((newIndex = page.indexOf("<a", index)) != -1
                || (newIndex = page.indexOf("<A", index)) != -1) {
            index = newIndex;
            iEndAngle = page.indexOf(">", index);
            if ((ihref = page.indexOf("href", index)) != -1
                    || (ihref = page.indexOf("HREF", index)) != -1) {
                iURL = page.indexOf("\"", ihref) + 1;
                if ((iURL != -1) && (iEndAngle != -1) && (iURL < iEndAngle)) {
                    iCloseQuote = page.indexOf("\"", iURL);
                    iHatchMark = page.indexOf("#", iURL);
                    if ((iCloseQuote != -1) && (iCloseQuote < iEndAngle)) {
                        iEnd = iCloseQuote;
                        if ((iHatchMark != -1) && (iHatchMark < iCloseQuote)) {
                            iEnd = iHatchMark;
                        }
                        String newUrlString = page.substring(iURL, iEnd).toLowerCase();
                        URL newUrl = null;
                        try {
                            newUrl = new URL(url, newUrlString);
                            results.add(newUrl);
                        } catch (MalformedURLException e) {
                            //ignore invalid urls
                        }
                    }
                }
            }
            index = iEndAngle;
        }
        return results;
    }

    /**
     * This class tries to simulate a concurrent queue
     */
    private static class UrlQueue {
        // it consists of many small queues
        final int LIST_COUNT = 1000;
        int emptyPos = 0;
        HashMap<Integer, LinkedList<URL>> listMap =
                new HashMap<Integer, LinkedList<URL>>();
        private final Object[] LIST_LOCK = new Object[LIST_COUNT];

        public UrlQueue() {
            for (int i = 0; i < LIST_COUNT; i++) {
                listMap.put(i, new LinkedList<URL>());
                LIST_LOCK[i] = new Object();
            }
        }

        public boolean isEmpty() {
            return emptyPos != -1;
        }

        // there's no lock for emptyPos, so cannot guarantee emptyPos won't change,
        // but doesn't hurt, and when hit miss is rare, should work well
        public void add(URL url) {
            int index = emptyPos;
            if (index == -1) {
                index = (int)(Math.random() * LIST_COUNT);
            }
            synchronized (LIST_LOCK[index]) {
                LinkedList<URL> current = listMap.get(index);
                current.add(url);
            }
            emptyPos = -1;
        }

        public URL poll() {
            int index = (int)(Math.random() * LIST_COUNT);
            URL url = null;
            synchronized (LIST_LOCK[index]) {
                LinkedList<URL> current = listMap.get(index);
                url = current.poll();
            }
            // if a thread does not get a url from a small queue,
            // store its index, and the next add() call will add
            // a url to that position
            if (url == null) {
                emptyPos = index;
            }
            else {
                emptyPos = -1;
            }
            return url;
        }
    }

    /**
     * This method prints the message to the console, and write to the work log
     */
    private static void output(String message) {
        System.out.println(message);
        try {
            logWriter.write(message + "\n");
        } catch (IOException e) {
            System.out.println("Write to work log not successfully");
        }
    }

    public static void main(String[] args) {
        System.out.println("\nPlease remember to create everything listed at code line 10 before running");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            // ignore
        }
        final int ARG_COUNT = 6;
        if (args.length != ARG_COUNT) {
            System.out.println(USAGE);
            System.exit(1);
        }
        String savePath = null;
        int index = 0;
        while (index < args.length) {
            if (args[index].equals("-path")) {
                savePath = args[index + 1];
                if (savePath == null) {
                    System.out.println(USAGE);
                    System.exit(1);
                }
                final Path docDir = Paths.get(savePath);
                if (!Files.isReadable(docDir)) {
                    System.out.println("Document directory '" + docDir.toAbsolutePath() + "' does not "
                            + "exist or is not readable, please check the path");
                    System.exit(1);
                }
                if (!Files.isDirectory(docDir)) {
                    System.out.println("Please provide the path of a directory");
                    System.exit(1);
                }
                if (!savePath.endsWith(File.separator)) {
                    savePath += File.separator;
                }
                index += 2;
            }
            else if (args[index].equals("-max")) {
                try {
                    searchLimit = Integer.parseInt(args[index + 1]);
                    index += 2;
                } catch (NumberFormatException e) {
                    System.out.println("Please provide an integer value for searchLimit");
                    System.exit(1);
                }
            }
            else if (args[index].equals("-id")) {
                try {
                    jobID = Integer.parseInt(args[index + 1]);
                    index += 2;
                } catch (NumberFormatException e) {
                    System.out.println("Please provide an integer value for jobID");
                    System.exit(1);
                }
            }
            else {
                System.out.println(USAGE);
                System.exit(1);
            }
        }
        // assume the roots directory has been created, read in the root file
        Scanner readFile = null;
        try {
            readFile = new Scanner(new FileReader(savePath + "roots" + File.separator + "root_" + jobID));
        } catch (FileNotFoundException e) {
            System.out.println("The root file does not exist");
            System.exit(1);
        }
        // create a work_log directory (if haven't) and create the work_log file
        String dirPath = savePath + "work_log" + File.separator;
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdir();
        }
        logWriter = null;
        try {
            logWriter = new FileWriter(dirPath + "workLog_" + jobID);
        } catch (IOException e) {
            System.out.println("Create workLog_" + jobID + " not successfully");
        }
        run(readFile, savePath);
    }
}
