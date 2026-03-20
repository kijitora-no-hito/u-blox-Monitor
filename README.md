# u-blox-Monitor
Author Rei Furukawa (kijitora-no-hito)

This software is a logging, visualization, and analysis tool for u-blox receivers (M8, F9, and X20 series), implemented in Python. It allows users to record RAW data (e.g., MEASX, SFRBX, RAWX) and analyze receiver performance through graphical visualization.

**License:**
This software is free to use, including for commercial purposes. However, no warranty is provided for any results or performance.
Redistribution or sale of this software, including modified versions containing this source code, is strictly prohibited.

**Contribution Policy**
This is a personal project and is not open for contributions.
Issues are disabled.

- Pull requests will be closed without review.
- No support or feature requests will be provided.

Feel free to use the code as you like, but please do not expect any response or maintenance.
**Exception**
I may occasionally respond to requests from personal contacts (e.g., discussion through at academic conferences in Japan related to navigation), but this is strictly on a private and case-by-case basis.

## How to Use

### 1. Requirements
- Python 3.11 or later

Install the required package:


pip install pyserial


---

### 2. Setup

- Install the appropriate u-blox USB driver on your Windows PC  
- Connect your u-blox receiver via USB  

---

### 3. Run

Start the application:


python ubxMonitor.py


The GUI will appear.
<img width="1852" height="952" alt="image" src="https://github.com/user-attachments/assets/22cb5ef5-b40d-454b-b925-8536c29aced9" />

---

### 4. Usage

- Select the serial port from the **Port** dropdown menu  
- Click the **Connect** button  
- Enable the **UBX checkbox** if you want to record UBX log files  
<img width="430" height="46" alt="image" src="https://github.com/user-attachments/assets/e43a3570-8928-495b-8644-fc744b46a721" />

-if you want to use ubx log, **Select File** and **PLAY**.

Navigation Settings shows your receiver's setting.
You can change through **Load** and **Apply** button.
<img width="565" height="53" alt="image" src="https://github.com/user-attachments/assets/6c80fb0a-004e-483b-81b2-437a3bfe239e" />

**Main GUI** shows signal reception status from RAWX and MEASX.
<img width="1684" height="492" alt="image" src="https://github.com/user-attachments/assets/ec67d8be-2819-4a2c-8d26-3dac30c72d1d" />

**SFRBX Monitor** shows reception status of Navigation data.
<img width="1757" height="555" alt="image" src="https://github.com/user-attachments/assets/e2bdf38b-ffdd-4f4e-992d-975ebb54b54d" />

**Trend Monitor** shows 5 minutes trend of RAW-MEASX.
<img width="1282" height="559" alt="image" src="https://github.com/user-attachments/assets/2f913845-e0ca-4060-b2a4-051fcc84b7d8" />



---

### Notes

This software is still under development.  
You may encounter bugs or incomplete features.

Feel free to explore and use it as you like.  
Use at your own risk :)
