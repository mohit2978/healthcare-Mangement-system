package com.hungrycoders.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents an appointment entity with details about doctor, patient, and status.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Appointment {

    private String id;
    private Patient patient;
    private Doctor doctor;
    private LocalDateTime appointmentTime;
    private AppointmentStatus status; // PENDING, CONFIRMED, REJECTED, COMPLETED
    private String notes;
    private String doctorComments;
}
