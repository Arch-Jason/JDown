import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private JPanel JDown;
    private JTextField Url;
    private static String filename;
    private JTextField Threads;
    private JButton downloadButton;
    private JLabel State;
    private JTextField Destination;
    private static String destination;
    private JButton setDestinationButton;
    private JScrollPane progressBarPane;
    private static final JFrame frame = new JFrame("JDown");

    private static ProgressBarUpdater progressBarUpdater;

    public Main() {
        setDestinationButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int option = fileChooser.showOpenDialog(frame);
                if(option == JFileChooser.APPROVE_OPTION) {
                    destination = fileChooser.getSelectedFile().getAbsolutePath();
                    Destination.setText(destination);
                }
            }
        });

        downloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                long size = 0;
                HttpURLConnection conn = null;
                try {
                    String url = Url.getText();
                    conn = (HttpURLConnection) new URI(url).toURL().openConnection();
                    conn.setRequestMethod("HEAD");
                    size = conn.getContentLengthLong();
                    filename = new File(new URI(url).toURL().getPath()).getName();
                } catch (Exception err) {
                    JOptionPane.showMessageDialog(frame, "Invalid URL:\n" + err.toString());
                    return;
                }
                int threadNum = Integer.parseInt(Threads.getText());

                if (size < threadNum) {
                    threadNum = (int) size;  // Ensure each thread has at least 1 byte to download
                }

                long[] partSizes = new long[threadNum + 1];
                partSizes[0] = 0; // Start of the first part
                long totalSize = size; // Total size of the file to be downloaded
                long eachPartSize = totalSize / threadNum; // Size each thread should download
                for (int i = 1; i < threadNum; i++) {
                    partSizes[i] = partSizes[i - 1] + eachPartSize;
                }
                partSizes[threadNum] = totalSize; // End of the last part is the total size
                if (size % threadNum != 0) {
                    long lastPartExtra = totalSize - (eachPartSize * threadNum);
                    partSizes[threadNum] = partSizes[threadNum - 1] + eachPartSize + lastPartExtra;
                }

                MultiThreadDownload[] multiThreadDownload = new MultiThreadDownload[threadNum];
                for(int i = 0; i < threadNum; i++) {
                    try {
                        multiThreadDownload[i] = new MultiThreadDownload(partSizes[i], partSizes[i + 1] - 1, Url.getText());
                    } catch (URISyntaxException | IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                for(int i = 0; i < threadNum; i++) {
                    multiThreadDownload[i].start();
                }
                State.setText("Downloading started.");
                progressBarUpdater = new ProgressBarUpdater(threadNum, multiThreadDownload);
                progressBarUpdater.start();

                checkDone checkDone = new checkDone(threadNum, partSizes, multiThreadDownload);
                checkDone.start();
            }
        });
    }

    public static void main(String[] args) {
        frame.setContentPane(new Main().JDown);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);
    }

    private static class MultiThreadDownload extends Thread {
        long start, end;
        String url;
        HttpURLConnection conn;
        public MultiThreadDownload(long start, long end, String url) throws URISyntaxException, IOException {
            this.start = start;
            this.end = end;
            this.url = url;
            this.conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        }
        @Override
        public void run() {
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                int responseCode = conn.getResponseCode();
                if(responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    InputStream inputStream = conn.getInputStream();
                    RandomAccessFile outputFile = new RandomAccessFile(destination + "/." + filename + "-" + start + "-" + end + ".part", "rw");
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputFile.write(buffer, 0, bytesRead);
                    }
                    outputFile.close();
                    inputStream.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public float getProgress() {
            long full_size = end - start + 1;
            return ((float) (new File(destination + "/." + filename + "-" + start + "-" + end + ".part").length()) / (float) full_size) * 100;
        }
    }

    private class ProgressBarUpdater extends Thread {
        private int threadNum;
        MultiThreadDownload[] multiThreadDownload;
        JProgressBar[] progressBars;
        public ProgressBarUpdater(int threadNum, MultiThreadDownload[] multiThreadDownload) {
            this.threadNum = threadNum;
            this.multiThreadDownload = multiThreadDownload;
            this.progressBars = new JProgressBar[threadNum];
            JPanel progressPanel = new JPanel();
            progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
            for (int i = 0; i < threadNum; i++) {
                this.progressBars[i] = new JProgressBar(0, 100);
                this.progressBars[i].setStringPainted(true);
                progressPanel.add(progressBars[i]);
            }
            progressBarPane.setViewportView(progressPanel);
        }
        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    for (int i = 0; i < threadNum; i++) {
                        progressBars[i].setValue((int) multiThreadDownload[i].getProgress());
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupt status
            }
        }
    }

    private class checkDone extends Thread {
        private MultiThreadDownload[] multiThreadDownloads;
        private int threadNum;
        private long[] partSizes;
        public checkDone(int threadNum, long[] partSizes, MultiThreadDownload[] multiThreadDownloads) {
            this.multiThreadDownloads = multiThreadDownloads;
            this.threadNum = threadNum;
            this.partSizes = partSizes;
        }
        @Override
        public void run() {
            try {
                for (MultiThreadDownload thread : multiThreadDownloads) {
                    thread.join();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            progressBarUpdater.interrupt();
            State.setText("Merging parts into one.");
            List<File> fileParts = new ArrayList<>();
            for (int i = 0; i < threadNum; i++) {
                long start = partSizes[i];
                long end = partSizes[i + 1] - 1;
                fileParts.add(new File(destination + "/." + filename + "-" + start + "-" + end + ".part"));
            }
            File outputFile = new File(destination + "/" + filename);
            try (BufferedOutputStream dos = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                for (File part : fileParts) {
                    try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(part))) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            dos.write(buffer, 0, bytesRead);
                        }
                    }
                }
                for (File part : fileParts) {
                    if (!part.delete()) {
                        throw new IOException("Cannot delete part files.");
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            State.setText("Done.");
        }
    }
}
