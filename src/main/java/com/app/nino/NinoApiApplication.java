package com.app.nino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling la bat buoc de Spring thuc su chay cac @Scheduled method
// (ReminderScheduler, LunarRecurrenceScheduler) — thieu no thi cac job nay
// duoc dang ky nhu bean binh thuong nhung KHONG BAO GIO duoc goi, khong loi,
// khong log gi ca. Day la nguyen nhan nhac nho khong bao gio bat len duoc.
@EnableScheduling
@SpringBootApplication
public class NinoApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NinoApiApplication.class, args);
	}

}
