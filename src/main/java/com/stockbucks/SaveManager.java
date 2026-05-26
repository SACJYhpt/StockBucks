package com.stockbucks;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.stockbucks.ai.mode.MarketMode;

public class SaveManager {
    // 存檔資料夾名稱
    private static final String SAVE_DIR = "saves";

    /**
     * 儲存模擬檔案到本地
     */
    public static boolean saveGame(User user, int dayIndex, String saveName) {
        return saveGame(user, dayIndex, saveName, MarketMode.HISTORY);
    }

    public static boolean saveGame(User user, int dayIndex, String saveName, MarketMode marketMode) {
        // 確保 saves 資料夾存在
        File dir = new File(SAVE_DIR);
        if (!dir.exists()) {
            dir.mkdir();
        }

        File file = new File(dir, saveName + ".dat");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            SaveData saveData = new SaveData(user, dayIndex, saveName, marketMode);
            oos.writeObject(saveData);
            System.out.println("存檔成功：" + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 從本地載入指定的模擬檔案
     */
    public static SaveData loadGame(String fileName) {
        File file = new File(SAVE_DIR, fileName);
        if (!file.exists()) return null;

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (SaveData) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean deleteGame(String fileName) {
        File file = new File(SAVE_DIR, fileName);
        return file.exists() && file.isFile() && file.delete();
    }

    /**
     * 獲取所有本地存檔清單
     */
    public static List<String> getSaveFiles() {
        List<String> saveList = new ArrayList<>();
        File dir = new File(SAVE_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
            if (files != null) {
                for (File f : files) {
                    saveList.add(f.getName()); // 會拿到諸如 "save1.dat"
                }
            }
        }
        return saveList;
    }
}
