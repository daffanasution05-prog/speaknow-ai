package com.speaknow.service;

import com.speaknow.model.User;
import com.speaknow.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.count() == 0) {
            System.out.println("🌱 Database is empty. Seeding default user...");
            User defaultUser = new User();
            defaultUser.setName("Learner");
            defaultUser.setTotalXp(0);
            defaultUser.setOverallScore(0.0);
            defaultUser.setLevel("Beginner");
            defaultUser.setPracticeCount(0);
            defaultUser.setGuidedCount(0);
            defaultUser.setChallengeCount(0);

            userRepository.save(defaultUser);
            System.out.println("✅ Default user seeded with ID=1 successfully!");
        } else {
            System.out.println("✅ Database already has users. Skipping seed.");
        }
    }
}
