package com.example.backend.assignment.repository;

import com.example.backend.assignment.entity.Bot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BotRepository extends JpaRepository<Bot, String> {

    Optional<Bot> findByName(String name);
}
