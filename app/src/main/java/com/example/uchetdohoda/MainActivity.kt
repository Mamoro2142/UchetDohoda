
package com.example.uchetdohoda

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val names = listOf(
    "Пики", "Вес", "Объём", "Боксы", "Часы"
)

private val defaults = mapOf(
    "Пики" to "0",
    "Вес" to "0",
    "Объём" to "0",
    "Боксы" to "0",
    "Часы" to "0"
)

data class DayEntry(
    val date: String,
    val values: List<Double>,
    val income: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                IncomeApp()
            }
        }
    }
}

@Composable
fun IncomeApp() {
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences(
            "income_app",
            Context.MODE_PRIVATE
        )
    }

    var tab by remember { mutableIntStateOf(0) }

    val rates = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(defaults)

            try {
                val saved = prefs.getString("rates", null)
                if (saved != null) {
                    val json = JSONObject(saved)
                    names.forEach { name ->
                        put(name, json.optString(name, "0"))
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    val inputs = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(defaults)
        }
    }

    val entries = remember {
        mutableStateListOf<DayEntry>().apply {
            try {
                val array = JSONArray(
                    prefs.getString("entries", "[]")
                )

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val jsonValues = obj.getJSONArray("values")

                    val values = (0 until 5).map { index ->
                        jsonValues.optDouble(index, 0.0)
                    }

                    add(
                        DayEntry(
                            date = obj.optString("date", ""),
                            values = values,
                            income = obj.optDouble("income", 0.0)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun saveRates() {
        val json = JSONObject()

        names.forEach { name ->
            json.put(name, rates[name] ?: "0")
        }

        prefs.edit()
            .putString("rates", json.toString())
            .apply()
    }

    fun saveEntries() {
        val array = JSONArray()

        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("date", entry.date)
            obj.put("values", JSONArray(entry.values))
            obj.put("income", entry.income)
            array.put(obj)
        }

        prefs.edit()
            .putString("entries", array.toString())
            .apply()
    }

    val quantities = names.map { name ->
        inputs[name]
            ?.replace(',', '.')
            ?.toDoubleOrNull() ?: 0.0
    }

    val prices = names.map { name ->
        rates[name]
            ?.replace(',', '.')
            ?.toDoubleOrNull() ?: 0.0
    }

    val income = quantities.zip(prices).sumOf { (q, p) ->
        q * p
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 1100.dp)
                ) {
                    Text(
                        text = "Учёт дохода",
                        modifier = Modifier.padding(
                            start = 20.dp,
                            top = 16.dp,
                            bottom = 12.dp
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    TabRow(selectedTabIndex = tab) {
                        listOf("Ввод", "Вахта", "Тарифы")
                            .forEachIndexed { index, title ->
                                Tab(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    text = { Text(title) }
                                )
                            }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1100.dp)
                    .fillMaxHeight()
            ) {
                when (tab) {
                    0 -> InputScreen(
                        inputs = inputs,
                        income = income,
                        onSave = {
                            if (entries.size >= 30) {
                                Toast.makeText(
                                    context,
                                    "Все 30 дней заполнены",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val date = SimpleDateFormat(
                                    "dd.MM.yyyy",
                                    Locale.getDefault()
                                ).format(Date())

                                entries.add(
                                    DayEntry(
                                        date = date,
                                        values = quantities,
                                        income = income
                                    )
                                )

                                saveEntries()

                                names.forEach { name ->
                                    inputs[name] = "0"
                                }

                                Toast.makeText(
                                    context,
                                    "Запись сохранена",
                                    Toast.LENGTH_SHORT
                                ).show()

                                tab = 1
                            }
                        }
                    )

                    1 -> ShiftScreen(entries = entries)

                    2 -> RatesScreen(
                        rates = rates,
                        onSave = {
                            saveRates()
                            Toast.makeText(
                                context,
                                "Тарифы сохранены",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun InputScreen(
    inputs: MutableMap<String, String>,
    income: Double,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
        ) {
            Text(
                text = "Введите количество",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            names.forEach { name ->
                NumberField(
                    label = name,
                    value = inputs[name] ?: "0",
                    onValueChange = { inputs[name] = it }
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Итого: ${money(income)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить")
            }
        }
    }
}

@Composable
fun RatesScreen(
    rates: MutableMap<String, String>,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
        ) {
            Text(
                text = "Стоимость за единицу",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            names.forEach { name ->
                NumberField(
                    label = "$name — тариф",
                    value = rates[name] ?: "0",
                    onValueChange = { rates[name] = it }
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить тарифы")
            }
        }
    }
}

@Composable
fun ShiftScreen(entries: List<DayEntry>) {
    val totals = (0 until 5).map { column ->
        entries.sumOf {
            it.values.getOrElse(column) { 0.0 }
        }
    }

    val totalIncome = entries.sumOf { it.income }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Вахта: ${entries.size} из 30 дней",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.horizontalScroll(
                rememberScrollState()
            )
        ) {
            Column {
                Row {
                    listOf(
                        "День", "Пики", "Вес", "Объём",
                        "Боксы", "Часы", "Доход"
                    ).forEach { title ->
                        TableCell(title, header = true)
                    }
                }

                HorizontalDivider()

                (0 until 30).forEach { index ->
                    val entry = entries.getOrNull(index)

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableCell("${index + 1}")

                        if (entry != null) {
                            entry.values.forEach { value ->
                                TableCell(number(value))
                            }
                        } else {
                            repeat(5) {
                                TableCell("—")
                            }
                        }

                        TableCell(
                            entry?.let { money(it.income) } ?: "—"
                        )
                    }

                    HorizontalDivider()
                }

                Row {
                    TableCell("Итого", header = true)

                    totals.forEach { total ->
                        TableCell(number(total), header = true)
                    }

                    TableCell(
                        money(totalIncome),
                        header = true
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Суммарный доход: ${money(totalIncome)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (
                newValue.isEmpty() ||
                newValue.matches(Regex("\\d*[.,]?\\d*"))
            ) {
                onValueChange(newValue)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
fun TableCell(
    text: String,
    header: Boolean = false
) {
    Box(
        modifier = Modifier
            .width(90.dp)
            .heightIn(min = 42.dp)
            .padding(
                horizontal = 4.dp,
                vertical = 8.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            }
        )
    }
}

fun number(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)

fun money(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)
    
