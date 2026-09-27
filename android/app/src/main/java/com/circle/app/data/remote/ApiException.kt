package com.circle.app.data.remote

import java.io.IOException

class ApiException(val statusCode: Int) : IOException("API request failed ($statusCode)")
