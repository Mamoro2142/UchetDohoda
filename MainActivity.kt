package com.example.uchetdohoda

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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

private val names = listOf("Пики", "Вес", "Объём", "Боксы", "Часы")
private val defaults = names.associateWith { "0" }
private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

data class DayEntry(val id: Long, val date: String, val values: List<Double>, val income: Double)
data class MoneyOperation(val id: Long, val date: String, val type: String, val amount: Double, val note: String)
data class WorkShift(
    val id: Long,
    val startDate: String,
    val endDate: String? = null,
    val entries: List<DayEntry> = emptyList(),
    val operations: List<MoneyOperation> = emptyList()
) {
    val gross: Double get() = entries.sumOf { it.income }
    val advances: Double get() = operations.filter { it.type == "Аванс" }.sumOf { it.amount }
    val fines: Double get() = operations.filter { it.type == "Штраф" }.sumOf { it.amount }
    val payout: Double get() = gross - advances - fines
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { IncomeApp() } }
    }
}

@Composable
fun IncomeApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("income_app", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    val rates = remember {
        mutableStateMapOf<String, String>().apply {
            putAll(defaults)
            try {
                prefs.getString("rates", null)?.let { saved ->
                    val json = JSONObject(saved)
                    names.forEach { put(it, json.optString(it, "0")) }
                }
            } catch (_: Exception) { }
        }
    }
    val inputs = remember { mutableStateMapOf<String, String>().apply { putAll(defaults) } }
    val shifts = remember {
        mutableStateListOf<WorkShift>().apply {
            try {
                val saved = prefs.getString("shifts", null)
                if (saved != null) {
                    val array = JSONArray(saved)
                    for (i in 0 until array.length()) add(shiftFromJson(array.getJSONObject(i)))
                } else {
                    // Import data from the previous app version without discarding existing entries.
                    val old = JSONArray(prefs.getString("entries", "[]"))
                    val migrated = mutableListOf<DayEntry>()
                    for (i in 0 until old.length()) {
                        val obj = old.getJSONObject(i)
                        val vals = obj.getJSONArray("values")
                        migrated.add(DayEntry(i.toLong() + 1, obj.optString("date", today()),
                            (0 until 5).map { vals.optDouble(it, 0.0) }, obj.optDouble("income", 0.0)))
                    }
                    if (migrated.isNotEmpty()) add(WorkShift(1, migrated.first().date, entries = migrated))
                }
            } catch (_: Exception) { }
        }
    }
    var nextId by remember { mutableLongStateOf((shifts.flatMap { it.entries.map { e -> e.id } + it.operations.map { o -> o.id } + listOf(it.id) }.maxOrNull() ?: 0L) + 1L) }
    var editing by remember { mutableStateOf<DayEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<Pair<Long, DayEntry>?>(null) }
    var operationDialog by remember { mutableStateOf(false) }

    fun saveRates() {
        val json = JSONObject(); names.forEach { json.put(it, rates[it] ?: "0") }
        prefs.edit().putString("rates", json.toString()).apply()
    }
    fun saveShifts() {
        val array = JSONArray(); shifts.forEach { array.put(shiftToJson(it)) }
        prefs.edit().putString("shifts", array.toString()).apply()
    }
    fun updateShift(updated: WorkShift) {
        val index = shifts.indexOfFirst { it.id == updated.id }
        if (index >= 0) { shifts[index] = updated; saveShifts() }
    }
    val activeShift = shifts.lastOrNull { it.endDate == null }
    val quantities = names.map { inputs[it]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 }
    val prices = names.map { rates[it]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 }
    val income = quantities.zip(prices).sumOf { (q, p) -> q * p }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(Modifier.fillMaxWidth().widthIn(max = 1100.dp)) {
                Text("Учёт дохода", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                ScrollableTabRow(selectedTabIndex = tab) {
                    listOf("Ввод", "Вахты", "Статистика", "Тарифы").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxWidth().widthIn(max = 1100.dp).fillMaxHeight()) {
                when (tab) {
                    0 -> InputScreen(inputs, income, activeShift != null, onSave = {
                        val active = shifts.lastOrNull { it.endDate == null }
                        if (active == null) Toast.makeText(context, "Сначала начните вахту во вкладке «Вахты»", Toast.LENGTH_LONG).show()
                        else {
                            val entry = DayEntry(nextId++, today(), quantities, income)
                            updateShift(active.copy(entries = active.entries + entry))
                            names.forEach { inputs[it] = "0" }
                            Toast.makeText(context, "Запись сохранена", Toast.LENGTH_SHORT).show()
                            tab = 1
                        }
                    })
                    1 -> ShiftsScreen(
                        shifts = shifts,
                        onStart = {
                            if (shifts.any { it.endDate == null }) Toast.makeText(context, "У вас уже есть активная вахта", Toast.LENGTH_SHORT).show()
                            else { shifts.add(WorkShift(nextId++, today())); saveShifts() }
                        },
                        onFinish = { shift -> updateShift(shift.copy(endDate = today())) },
                        onAddOperation = { operationDialog = true },
                        onEdit = { editing = it },
                        onDelete = { shiftId, entry -> deleteCandidate = shiftId to entry }
                    )
                    2 -> StatisticsScreen(shifts)
                    3 -> RatesScreen(rates) { saveRates(); Toast.makeText(context, "Тарифы сохранены", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    if (editing != null) {
        EditDayDialog(entry = editing!!, prices = prices, onDismiss = { editing = null }, onSave = { changed ->
            val shift = shifts.firstOrNull { s -> s.entries.any { it.id == changed.id } }
            if (shift != null) updateShift(shift.copy(entries = shift.entries.map { if (it.id == changed.id) changed else it }))
            editing = null
        })
    }
    if (deleteCandidate != null) {
        AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("Удалить запись?") },
            text = { Text("Запись за ${deleteCandidate!!.second.date} будет удалена без возможности восстановления.") },
            confirmButton = { TextButton(onClick = {
                val (shiftId, entry) = deleteCandidate!!
                shifts.firstOrNull { it.id == shiftId }?.let { s -> updateShift(s.copy(entries = s.entries.filterNot { it.id == entry.id })) }
                deleteCandidate = null
            }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") } })
    }
    if (operationDialog) {
        val active = shifts.lastOrNull { it.endDate == null }
        if (active == null) operationDialog = false
        else OperationDialog(onDismiss = { operationDialog = false }, onSave = { type, amount, note ->
            updateShift(active.copy(operations = active.operations + MoneyOperation(nextId++, today(), type, amount, note)))
            operationDialog = false
        })
    }
}

@Composable
fun InputScreen(inputs: MutableMap<String, String>, income: Double, hasActiveShift: Boolean, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            Text(if (hasActiveShift) "Введите количество за день" else "Сначала начните вахту во вкладке «Вахты»", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            names.forEach { name -> NumberField(name, inputs[name] ?: "0", { inputs[name] = it }) }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
            Text("Итого: ${money(income)} ₽", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSave, enabled = hasActiveShift, modifier = Modifier.fillMaxWidth()) { Text("Сохранить день") }
        }
    }
}

@Composable
fun RatesScreen(rates: MutableMap<String, String>, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            Text("Стоимость за единицу", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp))
            names.forEach { name -> NumberField("$name — тариф", rates[name] ?: "0", { rates[name] = it }) }
            Spacer(Modifier.height(16.dp)); Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Сохранить тарифы") }
        }
    }
}

@Composable
fun ShiftsScreen(shifts: List<WorkShift>, onStart: () -> Unit, onFinish: (WorkShift) -> Unit,
                 onAddOperation: () -> Unit, onEdit: (DayEntry) -> Unit, onDelete: (Long, DayEntry) -> Unit) {
    val active = shifts.lastOrNull { it.endDate == null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = active == null, modifier = Modifier.weight(1f)) { Text("Начать вахту") }
            Button(onClick = { active?.let(onFinish) }, enabled = active != null, modifier = Modifier.weight(1f)) { Text("Завершить вахту") }
        }
        if (shifts.isEmpty()) Text("Вахт пока нет. Нажмите «Начать вахту».", Modifier.padding(16.dp))
        shifts.asReversed().forEach { shift ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Вахта: ${shift.startDate} — ${shift.endDate ?: "идёт"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Дней: ${shift.entries.size}   Начислено: ${money(shift.gross)} ₽")
                    Text("Авансы: ${money(shift.advances)} ₽   Штрафы: ${money(shift.fines)} ₽")
                    Text("К выплате: ${money(shift.payout)} ₽", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (shift.endDate == null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onAddOperation, modifier = Modifier.fillMaxWidth()) { Text("Добавить аванс или штраф") }
                    }
                    if (shift.operations.isNotEmpty()) {
                        Text("Финансовые операции", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        shift.operations.forEach { op -> Text("${op.date}: ${op.type} ${money(op.amount)} ₽${if (op.note.isBlank()) "" else " — ${op.note}"}") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Дневные записи", fontWeight = FontWeight.SemiBold)
                    if (shift.entries.isEmpty()) Text("Записей пока нет")
                    shift.entries.forEach { entry ->
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${entry.date} — ${money(entry.income)} ₽", fontWeight = FontWeight.Medium)
                                Text(names.zip(entry.values).joinToString(" · ") { (name, value) -> "$name: ${number(value)}" }, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onEdit(entry) }) { Text("Изм.") }
                            TextButton(onClick = { onDelete(shift.id, entry) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatisticsScreen(shifts: List<WorkShift>) {
    val monthFormat = remember { SimpleDateFormat("MM.yyyy", Locale.getDefault()) }
    val months = (shifts.flatMap { s -> s.entries.map { it.date } + s.operations.map { it.date } }).mapNotNull {
        try { monthFormat.format(dateFormat.parse(it)!!) } catch (_: Exception) { null }
    }.distinct().sortedBy { try { monthFormat.parse(it)?.time ?: 0L } catch (_: Exception) { 0L } }.asReversed()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Статистика заработка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (months.isEmpty()) Text("Статистика появится после сохранения дневных записей.")
        months.forEach { month ->
            val entries = shifts.flatMap { it.entries }.filter { it.date.endsWith(month) }
            val ops = shifts.flatMap { it.operations }.filter { it.date.endsWith(month) }
            val gross = entries.sumOf { it.income }
            val advances = ops.filter { it.type == "Аванс" }.sumOf { it.amount }
            val fines = ops.filter { it.type == "Штраф" }.sumOf { it.amount }
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(month, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Начислено: ${money(gross)} ₽")
                    Text("Отработано дней: ${entries.size}")
                    Text("Среднее за день: ${money(if (entries.isEmpty()) 0.0 else gross / entries.size)} ₽")
                    Text("Авансы: ${money(advances)} ₽")
                    Text("Штрафы: ${money(fines)} ₽")
                    Text("К выплате по операциям месяца: ${money(gross - advances - fines)} ₽", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp)); Text("Итоги по вахтам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        shifts.asReversed().forEach { shift ->
            Text("${shift.startDate} — ${shift.endDate ?: "идёт"}: начислено ${money(shift.gross)} ₽, авансы ${money(shift.advances)} ₽, штрафы ${money(shift.fines)} ₽, к выплате ${money(shift.payout)} ₽", Modifier.padding(vertical = 6.dp))
            HorizontalDivider()
        }
    }
}

@Composable
fun EditDayDialog(entry: DayEntry, prices: List<Double>, onDismiss: () -> Unit, onSave: (DayEntry) -> Unit) {
    val values = remember(entry.id) { mutableStateListOf(*entry.values.map { number(it) }.toTypedArray()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Редактировать день") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("Дата: ${entry.date}")
                names.forEachIndexed { i, name -> NumberField(name, values[i], { values[i] = it }) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val nums = values.map { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
            onSave(entry.copy(values = nums, income = nums.zip(prices).sumOf { (quantity, price) -> quantity * price }))
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
fun OperationDialog(onDismiss: () -> Unit, onSave: (String, Double, String) -> Unit) {
    var type by remember { mutableStateOf("Аванс") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Финансовая операция") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "Аванс", onClick = { type = "Аванс" }, label = { Text("Аванс") })
                    FilterChip(selected = type == "Штраф", onClick = { type = "Штраф" }, label = { Text("Штраф") })
                }
                NumberField("Сумма, ₽", amount, { amount = it })
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Комментарий (необязательно)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = (amount.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0.0, onClick = { onSave(type, amount.replace(',', '.').toDoubleOrNull() ?: 0.0, note.trim()) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { newValue ->
        if (newValue.isEmpty() || newValue.matches(Regex("\\d*[.,]?\\d*"))) onValueChange(newValue)
    }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

fun today(): String = dateFormat.format(Date())
fun number(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
fun money(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

private fun shiftToJson(shift: WorkShift): JSONObject {
    val obj = JSONObject().put("id", shift.id).put("startDate", shift.startDate)
    if (shift.endDate != null) obj.put("endDate", shift.endDate)
    val entries = JSONArray(); shift.entries.forEach { e ->
        entries.put(JSONObject().put("id", e.id).put("date", e.date).put("values", JSONArray(e.values)).put("income", e.income))
    }
    val operations = JSONArray(); shift.operations.forEach { op ->
        operations.put(JSONObject().put("id", op.id).put("date", op.date).put("type", op.type).put("amount", op.amount).put("note", op.note))
    }
    return obj.put("entries", entries).put("operations", operations)
}

private fun shiftFromJson(obj: JSONObject): WorkShift {
    val entries = mutableListOf<DayEntry>()
    val entryArray = obj.optJSONArray("entries") ?: JSONArray()
    for (i in 0 until entryArray.length()) {
        val e = entryArray.getJSONObject(i); val vals = e.optJSONArray("values") ?: JSONArray()
        entries.add(DayEntry(e.optLong("id", i.toLong() + 1), e.optString("date", ""), (0 until 5).map { vals.optDouble(it, 0.0) }, e.optDouble("income", 0.0)))
    }
    val operations = mutableListOf<MoneyOperation>()
    val opArray = obj.optJSONArray("operations") ?: JSONArray()
    for (i in 0 until opArray.length()) {
        val o = opArray.getJSONObject(i)
        operations.add(MoneyOperation(o.optLong("id", 100000L + i), o.optString("date", ""), o.optString("type", "Аванс"), o.optDouble("amount", 0.0), o.optString("note", "")))
    }
    val endDate = if (obj.isNull("endDate")) null else obj.optString("endDate").takeIf { it.isNotBlank() && it != "null" }
    return WorkShift(obj.optLong("id", 1), obj.optString("startDate", today()), endDate, entries, operations)
}
