package com.paytm.assignment.repository;

import com.paytm.assignment.entity.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
}
