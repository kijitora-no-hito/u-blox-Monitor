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

---

### 4. Usage

- Select the serial port from the **Port** dropdown menu  
- Click the **Connect** button  
- Enable the **UBX checkbox** if you want to record UBX log files  

---

### Notes

This software is still under development.  
You may encounter bugs or incomplete features.

Feel free to explore and use it as you like.  
Use at your own risk :)
