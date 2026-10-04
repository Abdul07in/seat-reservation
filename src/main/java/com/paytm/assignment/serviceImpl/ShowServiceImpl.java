package com.paytm.assignment.serviceImpl;

import com.paytm.assignment.constant.ApiErrorCode;
import com.paytm.assignment.constant.ReservationStatus;
import com.paytm.assignment.constant.SeatStatus;
import com.paytm.assignment.dto.request.CreateShowRequest;
import com.paytm.assignment.dto.response.CreateShowResponse;
import com.paytm.assignment.dto.response.SeatStateResponse;
import com.paytm.assignment.dto.response.ShowStateResponse;
import com.paytm.assignment.entity.SeatEntity;
import com.paytm.assignment.entity.ShowEntity;
import com.paytm.assignment.exception.ApiException;
import com.paytm.assignment.repository.ReservationSeatRepository;
import com.paytm.assignment.repository.SeatRepository;
import com.paytm.assignment.repository.ShowRepository;
import com.paytm.assignment.service.ShowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShowServiceImpl implements ShowService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationSeatRepository reservationSeats;

    private static SeatStateResponse seatState(SeatEntity seat, SeatStatus status) {
        return new SeatStateResponse(seat.getSeatLabel(), status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, message);
    }

    @Transactional
    public CreateShowResponse create(CreateShowRequest request) {
        List<String> normalizedLabels = request.seats().stream().map(String::trim).toList();
        if (normalizedLabels.stream().distinct().count() != normalizedLabels.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                    "Seat labels must remain unique after trimming whitespace");
        }
        ShowEntity show = shows.saveAndFlush(new ShowEntity(request.name().trim(), request.pricePaise(),
                request.perUserLimit() == null ? 4 : request.perUserLimit()));
        List<SeatEntity> showSeats = normalizedLabels.stream().map(label -> new SeatEntity(show, label)).toList();
        seats.saveAll(showSeats);
        log.info("show_created show_id={} seat_count={}", show.getId(), showSeats.size());
        return new CreateShowResponse(show.getId().toString(), show.getName(),
                showSeats.stream().sorted(java.util.Comparator.comparing(SeatEntity::getSeatLabel))
                        .map(seat -> seatState(seat, SeatStatus.AVAILABLE)).toList());
    }

    @Transactional(readOnly = true)
    public ShowStateResponse getState(UUID showId) {
        ShowEntity show = shows.findById(showId).orElseThrow(() -> notFound("Show not found"));
        List<SeatEntity> showSeats = seats.findAllByShow_IdOrderBySeatLabel(showId);
        Set<UUID> confirmed = new HashSet<>(reservationSeats
                .findAllByShowIdAndReservationStatus(showId, ReservationStatus.CONFIRMED).stream()
                .map(link -> link.getSeat().getId()).toList());
        List<SeatStateResponse> states = showSeats.stream()
                .map(seat -> seatState(seat, confirmed.contains(seat.getId()) ? SeatStatus.CONFIRMED : SeatStatus.AVAILABLE))
                .toList();
        int confirmedCount = (int) states.stream().filter(state -> state.status().equals("confirmed")).count();
        int availableCount = states.size() - confirmedCount;
        return new ShowStateResponse(show.getId().toString(), show.getName(), states.size(),
                new ShowStateResponse.SeatCounts(availableCount, 0, confirmedCount), states);
    }
}
