package com.hzxs163.diantai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 进程内的轻量共享状态：列表顺序、url→台、ICY 标题。都不落盘。 */
final class UiBridge {
    static volatile Map<String, Station> byUrl = new HashMap<>();
    static volatile List<String> queue = new ArrayList<>();

    private UiBridge() {}
}
