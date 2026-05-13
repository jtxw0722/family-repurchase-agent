package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/11:06
 * @Description: 复核应用服务，提供待复核记录查询能力。
 */
@Service
public class ReviewApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final ReviewItemRepository reviewItemRepository;

    public ReviewApplicationService(DatabaseInitializer databaseInitializer, ReviewItemRepository reviewItemRepository) {
        this.databaseInitializer = databaseInitializer;
        this.reviewItemRepository = reviewItemRepository;
    }

    public List<ReviewItem> listPending() {
        databaseInitializer.initialize();
        return reviewItemRepository.listPending();
    }
}
