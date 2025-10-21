package com.ssafy.test.snapshot.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {

    /**
        1.일정 시간을 주기로 스케쥴러를 실행.
        2.CG:dbwriter가 consume하고 있는 스트림들에서 데이터를 가져옴, 제일 마지막에 consume한 데이터의 entry_id을 기록해 두고 해당 id값보다 작은 값 들을 제거. ex)XTRIM mystream MINID 1739481200010-0
        3.가져온 데이터를 임시로 caffeine에 적재해 둔다.
        4.각 data가 속하는 청크에 속하는 delta 정보들 중에서 tombstone에 없는 delta를 조회.
        5.가져온 data들과 조회한 data를 가지고 glb파일을 생성.
        6.성공적으로 glb파일이 생성된다면 caffeine에서 적재해둔 데이터 삭제
        7.glb파일을 정해진 규칭에 따라 명명, 적재.
     */
    @Scheduled
    public void makeSnapshot() {



    }

}
